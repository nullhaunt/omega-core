package com.omegarealm.core.command.paper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.omegarealm.core.RuntimeState;
import com.omegarealm.core.authorization.AuthorizationSnapshot;
import com.omegarealm.core.authorization.Role;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class HelpCommandTest {

    @Test
    void helpListsOnlyCommandsAllowedByTheCurrentSnapshot() throws Exception {
        try (Harness harness = new Harness()) {
            Player player = harness.player();
            var source = harness.source(player, player);
            harness.authorities.put(player, new AuthorizationSnapshot(UUID.randomUUID(), Role.PLAYER, 1));
            assertEquals(1, harness.dispatcher.execute("help", source));
            assertTrue(harness.messages.stream().anyMatch(message -> message.contains("/help")));
            assertTrue(harness.messages.stream().anyMatch(message -> message.contains("/profile")));
            assertTrue(harness.messages.stream().noneMatch(message -> message.contains("/role")));
            assertTrue(harness.messages.stream().noneMatch(message -> message.contains("/build")));
            assertTrue(harness.messages.stream().noneMatch(message -> message.contains("/status")));
            assertThrows(CommandSyntaxException.class, () -> harness.dispatcher.execute("help role", source));
            assertThrows(CommandSyntaxException.class, () -> harness.dispatcher.execute("help build", source));
            harness.messages.clear();
            assertEquals(1, harness.dispatcher.execute("help profile", source));
            assertEquals(List.of("Ω | View a player's Omega profile: /profile [current-username|UUID]"),
                    harness.messages);
            var suggestions = harness.dispatcher.getCompletionSuggestions(harness.dispatcher.parse("help r", source)).get();
            assertTrue(suggestions.isEmpty());

            harness.messages.clear();
            harness.authorities.put(player, new AuthorizationSnapshot(UUID.randomUUID(), Role.ADMIN, 2));
            assertEquals(1, harness.dispatcher.execute("help role", source));
            assertEquals(List.of("Ω | Manage realm-wide player roles: /role set <username|UUID> <PLAYER|MODERATOR> <reason...>"),
                    harness.messages);
            harness.messages.clear();
            assertEquals(1, harness.dispatcher.execute("help omegacore:role", source));
            assertEquals(List.of("Ω | Manage realm-wide player roles: /role set <username|UUID> <PLAYER|MODERATOR> <reason...>"),
                    harness.messages);
            harness.messages.clear();
            assertEquals(1, harness.dispatcher.execute("help", source));
            assertTrue(harness.messages.stream().anyMatch(message -> message.contains("/role")));
            assertTrue(harness.messages.stream().anyMatch(message -> message.contains("/build")));
            assertTrue(harness.messages.stream().anyMatch(message -> message.contains("/status")));
            assertTrue(harness.messages.stream().anyMatch(message -> message.contains("/paper")));
            assertTrue(harness.messages.stream().anyMatch(message -> message.contains("/spark")));

            harness.messages.clear();
            assertEquals(1, harness.dispatcher.execute("help", harness.source(harness.console, null)));
            assertTrue(harness.messages.stream().noneMatch(message -> message.contains("/profile")));
        }
    }

    @Test
    void cachedHelpParseCannotRevealCommandsAfterAuthorityLoss() throws Exception {
        try (Harness harness = new Harness()) {
            Player player = harness.player();
            harness.authorities.put(player, new AuthorizationSnapshot(UUID.randomUUID(), Role.ADMIN, 1));
            var parsed = harness.dispatcher.parse("help role", harness.source(player, player));
            harness.authorities.remove(player);
            CommandSyntaxException denied = assertThrows(CommandSyntaxException.class,
                    () -> harness.dispatcher.execute(parsed));
            assertEquals("help role", denied.getInput());
            assertTrue(harness.messages.isEmpty());
        }
    }

    private static final class Harness implements AutoCloseable {

        private final Map<Player, AuthorizationSnapshot> authorities = new IdentityHashMap<>();
        private final List<String> messages = new ArrayList<>();
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
            dispatcher.getRoot().addChild(new HelpCommand(access).node());
        }

        private Player player() {
            return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (object, method, arguments) -> {
                        if (method.getName().equals("sendMessage") && arguments[0] instanceof Component component) {
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

        @Override
        public void close() {
        }
    }
}
