package com.omegarealm.core.authorization.paper;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.omegarealm.core.authorization.AuthorizationActor;
import com.omegarealm.core.authorization.NativeCommandCatalog;
import com.omegarealm.core.command.CommandAuditEvent;
import com.omegarealm.core.command.CommandAuditEvent.Category;
import com.omegarealm.core.command.CommandAuditEvent.Outcome;
import com.omegarealm.core.command.paper.*;
import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.event.player.PlayerSignCommandPreprocessEvent;
import net.kyori.adventure.text.Component;
import net.minecraft.commands.CommandSourceStack;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * The sole version-specific boundary. Paper's public dispatcher exposes opaque vanilla nodes,
 * so its native dispatcher is needed to preserve handlers without console delegation.
 */
public final class NativeCommandFence implements Listener {

    private final Plugin plugin;
    private final PlayerPermissionAdapter permissions;
    private final CommandAccess access;
    private final CommandAudit audit;
    private final OmegaCommands commands;
    private final Runnable failure;
    private final BooleanSupplier ready;
    private final Runnable initialized;
    private volatile boolean installed;

    public NativeCommandFence(Plugin plugin, PlayerPermissionAdapter permissions,
                              CommandAccess access, CommandAudit audit, OmegaCommands commands,
                              Runnable failure, BooleanSupplier ready, Runnable initialized) {
        this.plugin = plugin;
        this.permissions = permissions;
        this.access = access;
        this.audit = audit;
        this.commands = commands;
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
            if (!commands.registered()) {
                throw new IllegalStateException("Omega command registration did not finish.");
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
                if (commands.owns(label)) {
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
        if (commands.owns(label)) {
            CommandCatalog.Definition definition = CommandCatalog.find(label);
            return definition != null && access.allows(definition, source);
        }
        String root = NativeCommandCatalog.root(label);
        if (root.equals("op") || root.equals("reload") || root.equals("rl")) {
            return false;
        }
        if (!(source.getSender() instanceof Player player)) {
            return true;
        }
        return installed && ready.getAsBoolean() && source.getExecutor() == player
                && permissions.authorization(player)
                .map(snapshot -> NativeCommandCatalog.allows(snapshot.role(), label)).orElse(false);
    }

    private boolean record(String label, CommandContext<CommandSourceStack> context) {
        if (commands.owns(label)) {
            return true;
        }
        CommandSourceStack source = context.getSource();
        if (!allows(label, source)) {
            return false;
        }
        AuthorizationActor actor;
        if (source.getSender() instanceof Player player) {
            actor = permissions.authorization(player).map(snapshot -> (AuthorizationActor) snapshot.actor()).orElse(null);
        } else if (source.getSender() == plugin.getServer().getConsoleSender() && source.getExecutor() == null) {
            actor = new AuthorizationActor.LocalConsole();
        } else if (source.getSender() instanceof RemoteConsoleCommandSender) {
            actor = new AuthorizationActor.Untrusted();
        } else {
            actor = null;
        }
        if (source.getSender() instanceof Player && actor == null) {
            return false;
        }
        if (actor != null) {
            StringBuilder action = new StringBuilder(NativeCommandCatalog.canonicalRoot(label));
            boolean first = true;
            for (var node : context.getNodes()) {
                if (!(node.getNode() instanceof LiteralCommandNode<?> literal)) {
                    continue;
                }
                if (first) {
                    first = false;
                } else {
                    action.append('.').append(NativeCommandCatalog.canonicalBranch(label, literal.getName()));
                }
            }
            CommandAuditEvent event = new CommandAuditEvent(UUID.randomUUID(), actor,
                    Category.MANAGEMENT, action.toString(), Outcome.INVOKED,
                    null, null, null);
            if (!audit.recordInvocation(event)) {
                CommandMessages.send(source.getSender(), CommandMessages.Kind.ERROR,
                        "Command audit is unavailable; this command was not executed.");
                return false;
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSignCommand(PlayerSignCommandPreprocessEvent event) {
        // Paper runs sign commands with elevated native permissions. They cannot carry staff authority.
        event.setCancelled(true);
        event.getPlayer().sendMessage(Component.translatable("command.unknown.command"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        if (event.getCommand().isBlank()) {
            return;
        }
        String label = event.getCommand().stripLeading().split("\\s+", 2)[0];
        String root = NativeCommandCatalog.root(label);
        if (root.equals("op")) {
            event.setCancelled(true);
            CommandMessages.send(event.getSender(), CommandMessages.Kind.ERROR,
                    "Omega keeps players non-OP. Use realm roles to grant authority.");
        } else if (root.equals("reload") || root.equals("rl")) {
            event.setCancelled(true);
            CommandMessages.send(event.getSender(), CommandMessages.Kind.ERROR,
                    "Omega does not support reload. Restart the server to apply changes.");
        } else if (!commands.owns(label) && !NativeCommandCatalog.reviewed(label)
                && (event.getSender() == plugin.getServer().getConsoleSender()
                || event.getSender() instanceof RemoteConsoleCommandSender)) {
            String path = NativeCommandCatalog.canonicalRoot(label);
            if (!path.matches("[a-z][a-z0-9._-]{0,127}")) {
                event.setCancelled(true);
                CommandMessages.send(event.getSender(), CommandMessages.Kind.ERROR,
                        "Command audit cannot identify this command; it was not executed.");
                return;
            }
            AuthorizationActor actor = event.getSender() == plugin.getServer().getConsoleSender()
                    ? new AuthorizationActor.LocalConsole() : new AuthorizationActor.Untrusted();
            CommandAuditEvent auditEvent = new CommandAuditEvent(UUID.randomUUID(), actor,
                    Category.MANAGEMENT, path, Outcome.INVOKED, null, null, null);
            if (!audit.recordInvocation(auditEvent)) {
                if (root.equals("stop")) {
                    // Shutdown must remain available even when persistence itself is down.
                    audit.record(actor, Category.MANAGEMENT, path, Outcome.INVOKED,
                            auditEvent.operationId(), null);
                    return;
                }
                event.setCancelled(true);
                CommandMessages.send(event.getSender(), CommandMessages.Kind.ERROR,
                        "Command audit is unavailable; this command was not executed.");
            }
        }
    }
}
