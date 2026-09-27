package com.omegarealm.core.authorization.paper;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.omegarealm.core.authorization.NativeCommandCatalog;
import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.event.player.PlayerSignCommandPreprocessEvent;
import net.kyori.adventure.text.Component;
import net.minecraft.commands.CommandSourceStack;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The sole version-specific boundary. Paper's public dispatcher exposes opaque vanilla nodes,
 * so its native dispatcher is needed to preserve handlers without console delegation.
 */
public final class NativeCommandFence implements Listener {

    private final Plugin plugin;
    private final PlayerPermissionAdapter permissions;
    private final Runnable failure;
    private final BooleanSupplier ready;
    private final Runnable initialized;
    private volatile boolean installed;

    public NativeCommandFence(Plugin plugin, PlayerPermissionAdapter permissions,
                              Runnable failure, BooleanSupplier ready, Runnable initialized) {
        this.plugin = plugin;
        this.permissions = permissions;
        this.failure = failure;
        this.ready = ready;
        this.initialized = initialized;
    }

    static boolean targetsPaper26_3(ServerBuildInfo build) {
        return build.brandId().equals(ServerBuildInfo.BRAND_PAPER_ID)
                && build.minecraftVersionId().equals("26.3");
    }

    public boolean installed() {
        return installed;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLoad(ServerLoadEvent event) {
        try {
            if (event.getType() != ServerLoadEvent.LoadType.STARTUP) {
                failure.run();
                return;
            }
            ServerBuildInfo build = ServerBuildInfo.buildInfo();
            if (!targetsPaper26_3(build)) {
                plugin.getLogger().severe("Native command enforcement targets Paper 26.3; refusing unsupported platform.");
                failure.run();
                return;
            }
            permissions.refreshPlan();
            // Clear persisted OP entries before admission; restarting must not revive OP authority.
            for (org.bukkit.OfflinePlayer operator : plugin.getServer().getOperators()) {
                operator.setOp(false);
            }
            var root = ((CraftServer) plugin.getServer()).getServer().getCommands().getDispatcher().getRoot();
            for (CommandNode<CommandSourceStack> node : List.copyOf(root.getChildren())) {
                if (!(node instanceof LiteralCommandNode<CommandSourceStack> literal)) {
                    throw new IllegalStateException("Unsupported native command root.");
                }
                String label = literal.getLiteral();
                var guard = new GuardedCommandTree<CommandSourceStack>(source -> allows(label, source),
                        source -> record(label, source));
                LiteralCommandNode<CommandSourceStack> replacement;
                if (label.equals("omega") || label.equals("omegacore:omega")) {
                    replacement = guard.copy(literal);
                } else if (!NativeCommandCatalog.reviewed(label)) {
                    replacement = guard.literal(label, literal.getCommand(),
                            source -> allows(label, source) && literal.canUse(source),
                            literal.getRedirect(), literal.getRedirectModifier(), literal.isFork());
                    literal.getChildren().forEach(replacement::addChild);
                } else if (!NativeCommandCatalog.branches(label).isEmpty()) {
                    replacement = guard.branches(literal, NativeCommandCatalog.branches(label).keySet(),
                            source -> source.getSender() instanceof Player);
                } else {
                    replacement = guard.copy(literal);
                }
                replacement.apiCommandMeta = literal.apiCommandMeta;
                root.removeCommand(label);
                root.addChild(replacement);
            }
            installed = true;
            plugin.getLogger().info("Native command enforcement installed; player OP is disabled.");
            initialized.run();
        } catch (LinkageError incompatible) {
            plugin.getLogger().severe("Native command enforcement could not link to this Paper build's command API.");
            failure.run();
        } catch (RuntimeException unavailable) {
            plugin.getLogger().severe("Native command enforcement could not initialize.");
            failure.run();
        }
    }

    private boolean allows(String label, CommandSourceStack source) {
        String root = NativeCommandCatalog.root(label);
        if (root.equals("op") || root.equals("reload") || root.equals("rl")) {
            return false;
        }
        if (!(source.getSender() instanceof Player player)) {
            return !NativeCommandCatalog.root(label).equals("omega")
                    || source.getSender() == plugin.getServer().getConsoleSender() && source.getExecutor() == null;
        }
        return installed && ready.getAsBoolean() && source.getExecutor() == player
                && permissions.authorization(player)
                .map(snapshot -> NativeCommandCatalog.root(label).equals("omega")
                        ? snapshot.role().includes(com.omegarealm.core.authorization.Role.ADMIN)
                        : NativeCommandCatalog.allows(snapshot.role(), label)).orElse(false);
    }

    private void record(String label, CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!NativeCommandCatalog.root(label).equals("omega") && source.getSender() instanceof Player player) {
            String action = context.getNodes().stream()
                    .filter(node -> node.getNode() instanceof LiteralCommandNode<?>)
                    .map(node -> node.getNode().getName()).collect(java.util.stream.Collectors.joining("."));
            permissions.authorization(player).ifPresent(snapshot -> plugin.getLogger().info(
                    "Native management command: actor=" + snapshot.playerUuid() + ", command=" + action + "."));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSignCommand(PlayerSignCommandPreprocessEvent event) {
        // Paper runs sign commands with elevated native permissions. They cannot carry staff authority.
        event.setCancelled(true);
        event.getPlayer().sendMessage(Component.translatable("command.unknown.command"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        String label = event.getCommand().stripLeading().split("\\s+", 2)[0];
        String root = NativeCommandCatalog.root(label);
        if (root.equals("op")) {
            event.setCancelled(true);
            event.getSender().sendPlainMessage("Omega keeps players non-OP. Use realm roles to grant authority.");
        } else if (root.equals("reload") || root.equals("rl")) {
            event.setCancelled(true);
            event.getSender().sendPlainMessage("Omega does not support reload. Restart the server to apply changes.");
        }
    }
}
