package com.omegarealm.core.command.paper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.omegarealm.core.RuntimeState;
import com.omegarealm.core.authorization.AuthorizationSnapshot;
import com.omegarealm.core.authorization.Role;
import com.omegarealm.core.player.*;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ProfileCommandTest {

    @Test
    void selfAndFloodgateTargetsResolveWithoutBlockingAndPresentOnThePlayerReply() throws Exception {
        Harness harness = new Harness();
        PlayerTarget self = new PlayerTarget.ByUuid(harness.viewerUuid);
        PlayerProfile ownProfile = new PlayerProfile(harness.viewerUuid, ".Unreference",
                Instant.parse("2026-09-28T00:00:00Z"), Instant.parse("2026-09-28T01:00:00Z"));
        PublicPlayerProfile publicProfile = new PublicPlayerProfile(ownProfile, Role.MODERATOR);

        assertEquals(1, harness.dispatcher.execute("profile", harness.playerSource()));
        assertEquals(List.of(self), harness.requests);
        assertEquals(List.of("Ω | Loading profile..."), harness.messages);
        harness.complete(self, PlayerTargetResolution.found(publicProfile));
        assertTrue(harness.presented.isEmpty());
        harness.replies.remove().run();
        assertEquals(List.of(publicProfile), harness.presented);

        PlayerTarget bedrock = new PlayerTarget.ByUsername(".Unreference");
        assertEquals(1, harness.dispatcher.execute("profile .Unreference", harness.playerSource()));
        assertEquals(List.of(self, bedrock), harness.requests);
        harness.complete(bedrock, PlayerTargetResolution.found(publicProfile));
        harness.replies.remove().run();
        assertEquals(List.of(publicProfile, publicProfile), harness.presented);
    }

    @Test
    void invalidMissingAndAmbiguousTargetsDoNotOpenDialogs() throws Exception {
        Harness harness = new Harness();
        assertEquals(0, harness.dispatcher.execute("profile name%", harness.playerSource()));
        assertTrue(harness.requests.isEmpty());
        assertTrue(harness.messages.getLast().contains("current username or full player ID"));

        PlayerTarget missing = new PlayerTarget.ByUsername("Unknown");
        assertEquals(1, harness.dispatcher.execute("profile Unknown", harness.playerSource()));
        harness.complete(missing, PlayerTargetResolution.missing());
        harness.replies.remove().run();
        assertTrue(harness.messages.getLast().contains("couldn't find a saved profile"));

        PlayerTarget ambiguous = new PlayerTarget.ByUsername("Duplicated");
        assertEquals(1, harness.dispatcher.execute("profile Duplicated", harness.playerSource()));
        harness.complete(ambiguous, PlayerTargetResolution.ambiguous());
        harness.replies.remove().run();
        assertTrue(harness.messages.getLast().contains("Use a player ID"));
        assertTrue(harness.presented.isEmpty());
    }

    @Test
    void consoleAndStalePlayerAuthorityCannotOpenAProfile() throws Exception {
        Harness harness = new Harness();
        assertFalse(harness.dispatcher.getRoot().getChild("profile").canUse(harness.consoleSource()));

        var parsed = harness.dispatcher.parse("profile", harness.playerSource());
        harness.authority = Optional.empty();
        assertThrows(CommandSyntaxException.class, () -> harness.dispatcher.execute(parsed));
        assertTrue(harness.requests.isEmpty());
    }

    @Test
    void failedLookupReportsAnErrorWithoutOpeningADialog() throws Exception {
        Harness harness = new Harness();
        PlayerTarget target = new PlayerTarget.ByUsername("Unknown");

        assertEquals(1, harness.dispatcher.execute("profile Unknown", harness.playerSource()));
        harness.lookups.get(target).completeExceptionally(new IllegalStateException("database unavailable"));
        harness.replies.remove().run();

        assertTrue(harness.messages.getLast().contains("couldn't load that profile"));
        assertFalse(harness.messages.getLast().contains("database unavailable"));
        assertTrue(harness.presented.isEmpty());
    }

    @Test
    void targetSuggestionsMatchConnectedNamesAndStopAfterAuthorityExpires() throws Exception {
        Harness harness = new Harness();
        harness.onlinePlayers.add(harness.namedPlayer("Alex"));
        harness.onlinePlayers.add(harness.namedPlayer("Alice"));
        harness.onlinePlayers.add(harness.namedPlayer(".Unreference"));
        CommandSourceStack source = harness.playerSource();

        var allNames = harness.dispatcher.getCompletionSuggestions(
                harness.dispatcher.parse("profile ", source)).get();
        assertEquals(Set.of("Alex", "Alice", ".Unreference"), allNames.getList().stream()
                .map(com.mojang.brigadier.suggestion.Suggestion::getText).collect(java.util.stream.Collectors.toSet()));

        var names = harness.dispatcher.getCompletionSuggestions(
                harness.dispatcher.parse("profile aL", source)).get();
        assertEquals(Set.of("Alex", "Alice"), names.getList().stream()
                .map(com.mojang.brigadier.suggestion.Suggestion::getText).collect(java.util.stream.Collectors.toSet()));

        var bedrock = harness.dispatcher.getCompletionSuggestions(
                harness.dispatcher.parse("profile .u", source)).get();
        assertEquals(List.of(".Unreference"), bedrock.getList().stream()
                .map(com.mojang.brigadier.suggestion.Suggestion::getText).toList());
        assertTrue(harness.requests.isEmpty());

        var parsed = harness.dispatcher.parse("profile a", source);
        harness.authority = Optional.empty();
        assertTrue(harness.dispatcher.getCompletionSuggestions(parsed).get().isEmpty());
    }

    private static final class Harness {

        private final UUID viewerUuid = UUID.randomUUID();
        private final List<PlayerTarget> requests = new ArrayList<>();
        private final List<String> messages = new ArrayList<>();
        private final List<PublicPlayerProfile> presented = new ArrayList<>();
        private final Queue<Runnable> replies = new ArrayDeque<>();
        private final Map<PlayerTarget, CompletableFuture<PlayerTargetResolution>> lookups = new HashMap<>();
        private final List<Player> onlinePlayers = new ArrayList<>();
        private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        private final Player viewer;
        private final ConsoleCommandSender console;
        private Optional<AuthorizationSnapshot> authority;

        private Harness() {
            viewer = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (object, method, arguments) -> {
                        if (method.getName().equals("sendMessage") && arguments[0] instanceof Component component) {
                            messages.add(PlainTextComponentSerializer.plainText().serialize(component));
                            return null;
                        }
                        throw new AssertionError(method.getName());
                    });
            console = (ConsoleCommandSender) Proxy.newProxyInstance(ConsoleCommandSender.class.getClassLoader(),
                    new Class<?>[]{ConsoleCommandSender.class}, (object, method, arguments) -> {
                        throw new AssertionError(method.getName());
                    });
            authority = Optional.of(new AuthorizationSnapshot(viewerUuid, Role.PLAYER, 0));

            CommandAccess access = new CommandAccess(() -> console, () -> RuntimeState.READY,
                    player -> player == viewer ? authority : Optional.empty());
            PlayerTargetResolver resolver = target -> {
                requests.add(target);
                return lookups.computeIfAbsent(target, ignored -> new CompletableFuture<>());
            };
            ProfileCommand command = new ProfileCommand(access, () -> resolver,
                    (player, reply) -> replies.add(reply),
                    (player, profile) -> presented.add(profile), Logger.getAnonymousLogger(),
                    () -> {
                        throw new AssertionError("Scheduling must remain available");
                    }, new OnlinePlayerTargetSuggestions(access, () -> onlinePlayers));
            dispatcher.getRoot().addChild(command.node());
        }

        private Player namedPlayer(String name) {
            return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (object, method, arguments) -> {
                        if (method.getName().equals("getName")) {
                            return name;
                        }
                        throw new AssertionError(method.getName());
                    });
        }

        private CommandSourceStack playerSource() {
            return source(viewer, viewer);
        }

        private CommandSourceStack consoleSource() {
            return source(console, null);
        }

        private CommandSourceStack source(CommandSender sender, Player executor) {
            return (CommandSourceStack) Proxy.newProxyInstance(CommandSourceStack.class.getClassLoader(),
                    new Class<?>[]{CommandSourceStack.class}, (object, method, arguments) -> switch (method.getName()) {
                        case "getSender" -> sender;
                        case "getExecutor" -> executor;
                        default -> throw new AssertionError(method.getName());
                    });
        }

        private void complete(PlayerTarget target, PlayerTargetResolution resolution) {
            lookups.get(target).complete(resolution);
            assertEquals(1, replies.size());
        }
    }
}
