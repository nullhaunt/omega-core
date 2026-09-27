package com.omegarealm.core.authorization.paper;

import com.omegarealm.core.authorization.AuthorizationSnapshot;
import com.omegarealm.core.authorization.Role;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class PlayerPermissionAdapterTest {

    private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> action) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, args) -> action.apply(method.getName(), args)));
    }

    @Test
    void deniesDefaultsRemovesOpAndAppliesRoleChangesOnTheOwnerBeforeAuthorizing() {
        Harness h = new Harness();
        h.join();
        assertFalse(h.op);
        assertEquals(Boolean.FALSE, h.attachment.getPermissions().get("minecraft.nbt.copy"));
        assertEquals(Boolean.FALSE, h.attachment.getPermissions().get("minecraft.command.time"));
        assertTrue(h.adapter.authorization(h.player).isPresent());

        h.current = new AuthorizationSnapshot(h.uuid, Role.ADMIN, 1);
        assertTrue(h.adapter.authorization(h.player).isEmpty());
        h.adapter.reconcile();
        assertEquals(Boolean.FALSE, h.attachment.getPermissions().get("minecraft.command.time"));
        h.tasks.remove().run();
        assertEquals(Boolean.TRUE, h.attachment.getPermissions().get("minecraft.command.time"));
        assertEquals(Boolean.FALSE, h.attachment.getPermissions().get("minecraft.nbt.copy"));
        assertEquals(Boolean.FALSE, h.attachment.getPermissions().get("bukkit.command.paper"));
        assertEquals(Role.ADMIN, h.adapter.authorization(h.player).orElseThrow().role());

        h.current = new AuthorizationSnapshot(h.uuid, Role.PLAYER, 2);
        assertTrue(h.adapter.authorization(h.player).isEmpty());
        h.adapter.reconcile();
        h.tasks.remove().run();
        assertEquals(Boolean.FALSE, h.attachment.getPermissions().get("minecraft.command.time"));
        assertEquals(3, h.commandUpdates);
    }

    @Test
    void delayedApplicationBecomesTerminalAndKicksWhenTheOwnerCanRun() {
        Harness h = new Harness();
        h.join();
        h.current = new AuthorizationSnapshot(h.uuid, Role.ADMIN, 1);
        h.adapter.reconcile();
        h.time.addAndGet(java.time.Duration.ofSeconds(5).toNanos());
        h.adapter.reconcile();
        assertTrue(h.adapter.authorization(h.player).isEmpty());
        assertEquals(1, h.tasks.size());
        h.tasks.remove().run();
        assertEquals(1, h.kicks);
        assertEquals(Boolean.FALSE, h.attachment.getPermissions().get("minecraft.command.time"));
        assertTrue(h.adapter.authorization(h.player).isEmpty());
    }

    @Test
    void queuedWorkCannotMutateARetiredConnection() {
        Harness h = new Harness();
        h.join();
        h.adapter.reconcile();
        h.adapter.onQuit(new PlayerQuitEvent(h.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        int updates = h.commandUpdates;
        h.tasks.remove().run();
        assertEquals(updates, h.commandUpdates);
        assertTrue(h.adapter.authorization(h.player).isEmpty());
        h.adapter.close();
    }

    @Test
    void changedPermissionRegistryDeniesAuthorityUntilNewDefaultsAreExplicitlyDenied() {
        Harness h = new Harness();
        h.current = new AuthorizationSnapshot(h.uuid, Role.ADMIN, 1);
        h.join();
        h.permissions.add(new Permission("new.permission", PermissionDefault.TRUE));
        h.adapter.refreshPlan();
        assertTrue(h.adapter.authorization(h.player).isEmpty());
        h.adapter.reconcile();
        h.tasks.remove().run();
        assertEquals(Boolean.FALSE, h.attachment.getPermissions().get("new.permission"));
        assertTrue(h.adapter.authorization(h.player).isPresent());
    }

    private static final class Harness {
        private final UUID uuid = UUID.randomUUID();
        private final AtomicLong time = new AtomicLong(1);
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private final Set<Permission> permissions = new HashSet<>(Set.of(
                new Permission("minecraft.nbt.copy", PermissionDefault.TRUE),
                new Permission("bukkit.command.paper", Map.of("bukkit.command.paper.fixlight", true))));
        private final Player player;
        private final PlayerPermissionAdapter adapter;
        private AuthorizationSnapshot current = AuthorizationSnapshot.initial(uuid);
        private boolean op = true;
        private int commandUpdates;
        private int kicks;
        private PermissionAttachment attachment;

        private Harness() {
            PluginManager manager = proxy(PluginManager.class, (method, args) -> switch (method) {
                case "getPermissions" -> Set.copyOf(permissions);
                default -> throw new AssertionError(method);
            });
            Server server = proxy(Server.class, (method, args) -> method.equals("getPluginManager") ? manager : null);
            Plugin plugin = proxy(Plugin.class, (method, args) -> switch (method) {
                case "getServer" -> server;
                case "isEnabled" -> true;
                default -> null;
            });
            EntityScheduler scheduler = proxy(EntityScheduler.class, (method, args) -> {
                if (method.equals("execute")) {
                    tasks.add((Runnable) args[1]);
                    return true;
                }
                throw new AssertionError(method);
            });
            player = proxy(Player.class, (method, args) -> switch (method) {
                case "getScheduler" -> scheduler;
                case "addAttachment" -> attachment;
                case "isOp" -> op;
                case "setOp" -> {
                    op = (boolean) args[0];
                    yield null;
                }
                case "updateCommands" -> {
                    commandUpdates++;
                    yield null;
                }
                case "kick" -> {
                    kicks++;
                    yield null;
                }
                case "recalculatePermissions", "removeAttachment" -> null;
                default -> throw new AssertionError("Unexpected player access: " + method);
            });
            attachment = new PermissionAttachment(plugin, player);
            adapter = new PlayerPermissionAdapter(plugin,
                    candidate -> candidate == player ? Optional.ofNullable(current) : Optional.empty(),
                    () -> fail("Unexpected platform failure"), time::get);
            adapter.refreshPlan();
        }

        private void join() {
            adapter.onJoin(new PlayerJoinEvent(player, Component.empty()));
        }
    }
}
