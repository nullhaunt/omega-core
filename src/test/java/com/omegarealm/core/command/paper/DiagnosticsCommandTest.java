package com.omegarealm.core.command.paper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.omegarealm.core.RuntimeState;
import com.omegarealm.core.authorization.AuthorizationSnapshot;
import com.omegarealm.core.authorization.Role;
import com.omegarealm.core.diagnostics.BuildIdentity;
import com.omegarealm.core.diagnostics.RuntimeDiagnostics;
import com.omegarealm.core.player.PlayerServiceDiagnostics;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticsCommandTest {

    @Test
    void onlyFreshManagementActorsCanReadDiagnostics() throws Exception {
        Harness harness = new Harness();
        Player player = harness.player();
        CommandSourceStack playerSource = harness.source(player, player);
        harness.authorities.put(player, new AuthorizationSnapshot(UUID.randomUUID(), Role.PLAYER, 1));

        assertThrows(CommandSyntaxException.class, () -> harness.dispatcher.execute("build", playerSource));
        assertThrows(CommandSyntaxException.class, () -> harness.dispatcher.execute("status", playerSource));
        assertEquals(0, harness.statusReads.get());
        assertTrue(harness.messages.isEmpty());

        harness.authorities.put(player, new AuthorizationSnapshot(UUID.randomUUID(), Role.MODERATOR, 2));
        assertThrows(CommandSyntaxException.class, () -> harness.dispatcher.execute("status", playerSource));

        harness.authorities.put(player, new AuthorizationSnapshot(UUID.randomUUID(), Role.ADMIN, 3));
        assertEquals(1, harness.dispatcher.execute("build", playerSource));
        assertEquals(List.of("Ω | BUILD\n  Version: 0.1.0-SNAPSHOT\n  Revision: aaaaaaaaaaaa"
                + "\n  Built: 2026-09-28 04:00 UTC"), harness.messages);
        assertEquals(NamedTextColor.AQUA, harness.playerComponents.getFirst().color());

        harness.messages.clear();
        assertEquals(1, harness.dispatcher.execute("status", playerSource));
        assertEquals(1, harness.statusReads.get());
        assertEquals(1, harness.messages.size());
        assertTrue(harness.messages.getFirst().startsWith("Ω | STATUS\n  State: Ready"));
        assertTrue(harness.messages.getFirst().contains("Uptime: 30s"));
        assertTrue(harness.messages.getFirst().contains(
                "Database: Last check 2.5s ago: connected, server lock held"));
        assertTrue(harness.messages.getFirst().contains(
                "Authorization: oldest check 4s ago · 1 expired connection"));
        assertTrue(harness.playerComponents.getLast().children().stream()
                .anyMatch(child -> child instanceof TextComponent text
                        && text.content().equals("Ready") && NamedTextColor.GREEN.equals(text.color())));

        harness.messages.clear();
        var parsed = harness.dispatcher.parse("status", playerSource);
        harness.authorities.remove(player);
        assertThrows(CommandSyntaxException.class, () -> harness.dispatcher.execute(parsed));
        assertEquals(1, harness.statusReads.get());
        assertTrue(harness.messages.isEmpty());

        assertEquals(1, harness.dispatcher.execute("status", harness.source(harness.console, null)));
        assertEquals(2, harness.statusReads.get());
        assertEquals(1, harness.messages.size());
        assertTrue(harness.messages.getFirst().startsWith("Ω | STATUS\n  State: Ready"));
        assertThrows(CommandSyntaxException.class,
                () -> harness.dispatcher.execute("status", harness.source(harness.console, player)));
    }

    private static final class Harness {
        private final Map<Player, AuthorizationSnapshot> authorities = new IdentityHashMap<>();
        private final List<String> messages = new ArrayList<>();
        private final List<Component> playerComponents = new ArrayList<>();
        private final AtomicInteger statusReads = new AtomicInteger();
        private final ConsoleCommandSender console = (ConsoleCommandSender) Proxy.newProxyInstance(
                ConsoleCommandSender.class.getClassLoader(), new Class<?>[]{ConsoleCommandSender.class},
                (object, method, arguments) -> {
                    if (method.getName().equals("sendPlainMessage")) {
                        messages.add((String) arguments[0]);
                        return null;
                    }
                    throw new AssertionError(method.getName());
                });
        private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();

        private Harness() {
            CommandAccess access = new CommandAccess(() -> console, () -> RuntimeState.READY,
                    player -> Optional.ofNullable(authorities.get(player)));
            BuildIdentity build = new BuildIdentity("0.1.0-SNAPSHOT", "a".repeat(12),
                    Instant.parse("2026-09-28T04:00:00Z"));
            PlayerServiceDiagnostics players = new PlayerServiceDiagnostics(true, 2, 2, 1,
                    Optional.of(Duration.ofMillis(2500)), false,
                    Optional.of(Duration.ofMillis(4000)), 1);
            RuntimeDiagnostics snapshot = new RuntimeDiagnostics(RuntimeState.READY, "dev-01",
                    "development", Duration.ofSeconds(30), Optional.of(players));
            CommandAudit audit = new CommandAudit(Logger.getAnonymousLogger(), "dev-01", () -> {
                throw new AssertionError("Diagnostics must not use the database worker.");
            });
            DiagnosticsCommand diagnostics = new DiagnosticsCommand(access, build, () -> {
                statusReads.incrementAndGet();
                return snapshot;
            }, audit);
            dispatcher.getRoot().addChild(diagnostics.buildNode());
            dispatcher.getRoot().addChild(diagnostics.statusNode());
        }

        private Player player() {
            return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (object, method, arguments) -> {
                        if (method.getName().equals("sendMessage") && arguments[0] instanceof Component component) {
                            playerComponents.add(component);
                            messages.add(PlainTextComponentSerializer.plainText().serialize(component));
                            return null;
                        }
                        throw new AssertionError(method.getName());
                    });
        }

        private CommandSourceStack source(CommandSender sender, Player executor) {
            return (CommandSourceStack) Proxy.newProxyInstance(CommandSourceStack.class.getClassLoader(),
                    new Class<?>[]{CommandSourceStack.class}, (object, method, arguments) -> switch (method.getName()) {
                        case "getSender" -> sender;
                        case "getExecutor" -> executor;
                        default -> throw new AssertionError(method.getName());
                    });
        }
    }
}
