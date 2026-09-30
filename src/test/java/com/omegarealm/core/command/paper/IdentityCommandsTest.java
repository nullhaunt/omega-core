package com.omegarealm.core.command.paper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.omegarealm.core.RuntimeState;
import com.omegarealm.core.authorization.AuthorizationSnapshot;
import com.omegarealm.core.authorization.Role;
import com.omegarealm.core.authorization.RoleChangeRequest;
import com.omegarealm.core.authorization.RoleChangeResult;
import com.omegarealm.core.command.CommandAuditEvent;
import com.omegarealm.core.player.*;
import com.omegarealm.core.scheduler.OmegaScheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class IdentityCommandsTest {

    @Test
    void selfStatusSetAndClearUseTheConnectionIdentityAndFreshRevision() throws Exception {
        try (Harness harness = new Harness()) {
            assertEquals(1, harness.execute("nickname"));
            harness.drain();
            assertTrue(harness.messages.getLast().contains("Nickname: Not set"));
            assertTrue(harness.messages.getLast().contains("/nickname clear"));

            harness.repository.nickname = new PlayerNickname(harness.uuid, null, 7);
            assertEquals(1, harness.execute("nickname Aeron"));
            assertTrue(harness.messages.getLast().contains("Not set"));
            harness.drain();
            assertEquals(new NicknameChange(harness.uuid, 7, "Aeron"), harness.repository.changes.getFirst());
            assertEquals("Ω | Your nickname is now Aeron.", harness.messages.getLast());
            assertEquals(1, harness.execute("nickname"));
            harness.drain();
            assertTrue(harness.messages.getLast().contains("Nickname: Aeron"));

            assertEquals(1, harness.execute("nickname CLEAR"));
            harness.drain();
            assertEquals(new NicknameChange(harness.uuid, 8, null), harness.repository.changes.getLast());
            assertEquals("Ω | Your nickname has been cleared.", harness.messages.getLast());
        }
    }

    @Test
    void invalidSyntaxAndStaffLabelsAreRejectedBeforeAnyProfileIo() throws Exception {
        try (Harness harness = new Harness()) {
            for (String input : List.of("ab", "Has space", "<red>Aeron", "Éron", "12345678901234567", "Moderator", "ADMIN", "Owner")) {
                assertEquals(0, harness.execute("nickname " + input));
            }
            assertEquals(0, harness.repository.nicknameReads);
            assertTrue(harness.repository.changes.isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(value = NicknameChangeResult.Status.class, names = {"TAKEN", "RESERVED", "STALE", "UNCHANGED", "NOT_FOUND"})
    void controlledRejectionsAndNoOpsNeverReportAnEditSuccess(NicknameChangeResult.Status status) throws Exception {
        try (Harness harness = new Harness()) {
            harness.repository.status = status;
            assertEquals(1, harness.execute(status == NicknameChangeResult.Status.UNCHANGED ? "nickname clear" : "nickname Aeron"));
            harness.drain();
            String reply = harness.messages.getLast();
            String expected = switch (status) {
                case TAKEN -> "Another player already uses";
                case RESERVED -> "reserved for an account";
                case STALE -> "changed while this request was pending";
                case UNCHANGED -> "don't have a nickname to clear";
                case NOT_FOUND -> "couldn't find your saved profile";
                default -> throw new AssertionError(status);
            };
            assertTrue(reply.contains(expected), reply);
            assertFalse(reply.contains("now Aeron"));
            assertFalse(reply.contains("revision"));
        }
    }

    @Test
    void senderRestrictionsAndCachedParsesRejectExpiredAuthority() throws Exception {
        try (Harness harness = new Harness()) {
            for (CommandSourceStack denied : List.of(harness.source(harness.console, null),
                    harness.source(harness.console, harness.viewer), harness.source(harness.viewer, null),
                    harness.source(harness.rcon, null))) {
                assertThrows(CommandSyntaxException.class, () -> harness.dispatcher.execute("nickname Aeron", denied));
            }
            assertThrows(CommandSyntaxException.class, () -> harness.dispatcher.execute("realname Aeron", harness.source(harness.rcon, null)));
            var parsed = harness.dispatcher.parse("nickname Aeron", harness.playerSource());
            harness.connected = false;
            assertThrows(CommandSyntaxException.class, () -> harness.dispatcher.execute(parsed));
            assertTrue(harness.repository.changes.isEmpty());
        }
    }

    @Test
    void missingTokenDoesNotFallBackToThePlayersUuidAndLateRepliesAreDropped() throws Exception {
        try (Harness harness = new Harness()) {
            harness.tokenAvailable = false;
            assertEquals(0, harness.execute("nickname Aeron"));
            assertTrue(harness.repository.changes.isEmpty());
            harness.tokenAvailable = true;
            harness.messages.clear();
            assertEquals(1, harness.execute("nickname Aeron"));
            harness.barrier();
            harness.connected = false;
            harness.drain();
            assertTrue(harness.messages.isEmpty());
            assertEquals("Aeron", harness.repository.nickname.nickname());
        }
    }

    @Test
    void realnameAcceptsOfflineAliasesAndUuidsForPlayersAndLocalConsole() throws Exception {
        try (Harness harness = new Harness()) {
            for (String name : List.of("Aeron", "AccountName", harness.repository.target.toString())) {
                assertEquals(1, harness.execute("realname " + name));
                harness.drain();
                assertEquals("Ω | Player identity\n  Account: AccountName\n  Nickname: Aeron", harness.messages.getLast());
            }
            assertEquals(1, harness.dispatcher.execute("realname Aeron", harness.source(harness.console, null)));
            harness.drain();
            assertTrue(harness.messages.getLast().contains("Account: AccountName"));
            assertEquals(4, harness.repository.lookups);
        }
    }

    @Test
    void realnameExplainsMissingAmbiguousAndMalformedTargetsAndUsesCatalogHelp() throws Exception {
        try (Harness harness = new Harness()) {
            assertEquals(0, harness.execute("realname"));
            assertTrue(harness.messages.getLast().contains("/realname <player>"));
            assertEquals(0, harness.execute("realname @a"));
            assertTrue(harness.messages.getLast().contains("nickname"));
            assertEquals(1, harness.execute("realname Unknown"));
            harness.drain();
            assertTrue(harness.messages.getLast().contains("No saved player"));
            assertEquals(1, harness.execute("realname Duplicate"));
            harness.drain();
            assertTrue(harness.messages.getLast().contains("More than one player"));
            assertEquals(1, harness.execute("help nickname"));
            assertTrue(harness.messages.getLast().contains("/nickname [name|clear]"));
            var suggestions = harness.dispatcher.getCompletionSuggestions(harness.dispatcher.parse("nickname c", harness.playerSource())).get();
            assertEquals(List.of("clear"), suggestions.getList().stream().map(suggestion -> suggestion.getText()).toList());
        }
    }

    private static final class Harness implements AutoCloseable {
        private final UUID uuid = UUID.randomUUID();
        private final UUID token = UUID.randomUUID();
        private final Repository repository = new Repository(uuid);
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
        private final Queue<Runnable> replies = new ConcurrentLinkedQueue<>();
        private final List<String> messages = new ArrayList<>();
        private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        private final Player viewer = proxy(Player.class);
        private final ConsoleCommandSender console = proxy(ConsoleCommandSender.class);
        private final RemoteConsoleCommandSender rcon = proxy(RemoteConsoleCommandSender.class);
        private final PlayerService service;
        private boolean connected = true;
        private boolean tokenAvailable = true;

        private Harness() throws Exception {
            Logger logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            service = worker.submit(() -> {
                var initialized = new PlayerService(repository, worker, deadlines, message -> fail(message),
                        logger, Clock.systemUTC(), PlayerServiceLimits.DEFAULT);
                initialized.startOnWorker();
                return initialized;
            }).get(2, TimeUnit.SECONDS);
            service.prepare(new PlayerAttempt(token, new PlayerIdentity(uuid, "SelfAccount"))).toCompletableFuture().get(2, TimeUnit.SECONDS);
            service.activate(token, Instant.now()).toCompletableFuture().get(2, TimeUnit.SECONDS);
            CommandAccess access = new CommandAccess(() -> console, () -> RuntimeState.READY,
                    player -> connected && player == viewer ? service.authorization(token) : Optional.empty());
            OmegaScheduler scheduler = new OmegaScheduler() {
                public void global(Runnable action) {
                    replies.add(action);
                }

                public Task repeatAsync(Duration delay, Duration period, Runnable action) {
                    throw new AssertionError("No new timers");
                }

                public void close() {
                }
            };
            CommandReplies commandReplies = new CommandReplies(access, scheduler,
                    (player, action) -> replies.add(() -> {
                        if (connected) {
                            action.run();
                        }
                    }), logger, () -> fail("Scheduling failure"));
            dispatcher.getRoot().addChild(new NicknameCommand(access, () -> service,
                    player -> tokenAvailable ? Optional.of(token) : Optional.empty(), commandReplies).node());
            dispatcher.getRoot().addChild(new RealnameCommand(access, () -> service, commandReplies,
                    new PlayerTargetSuggestions(access, service::suggestTargets)).node(StringArgumentType.greedyString()));
            dispatcher.getRoot().addChild(new HelpCommand(access).node());
        }

        private <T> T proxy(Class<T> type) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> {
                if (method.getName().equals("sendMessage") && args[0] instanceof Component component) {
                    messages.add(PlainTextComponentSerializer.plainText().serialize(component));
                    return null;
                }
                if (method.getName().equals("sendPlainMessage")) {
                    messages.add((String) args[0]);
                    return null;
                }
                throw new AssertionError(method.getName());
            }));
        }

        private CommandSourceStack source(CommandSender sender, Player executor) {
            return (CommandSourceStack) Proxy.newProxyInstance(CommandSourceStack.class.getClassLoader(),
                    new Class<?>[]{CommandSourceStack.class}, (object, method, args) -> switch (method.getName()) {
                        case "getSender" -> sender;
                        case "getExecutor" -> executor;
                        default -> throw new AssertionError(method.getName());
                    });
        }

        private CommandSourceStack playerSource() {
            return source(viewer, viewer);
        }

        private int execute(String command) throws CommandSyntaxException {
            return dispatcher.execute(command, playerSource());
        }

        private void barrier() throws Exception {
            worker.submit(() -> {
            }).get(2, TimeUnit.SECONDS);
        }

        private void drain() throws Exception {
            barrier();
            for (Runnable action; (action = replies.poll()) != null; ) {
                action.run();
            }
        }

        public void close() throws Exception {
            service.requestStop(Instant.now());
            try {
                worker.submit(service::closeOnWorker).get(2, TimeUnit.SECONDS);
            } finally {
                worker.shutdownNow();
                deadlines.shutdownNow();
                assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
                assertTrue(deadlines.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }

    private static final class Repository implements PlayerSessionRepository {
        private final UUID target = UUID.randomUUID();
        private final List<NicknameChange> changes = new CopyOnWriteArrayList<>();
        private PlayerNickname nickname;
        private int nicknameReads;
        private int lookups;
        private NicknameChangeResult.Status status = NicknameChangeResult.Status.CHANGED;

        private Repository(UUID uuid) {
            nickname = new PlayerNickname(uuid, null, 0);
        }

        public PlayerProfileRepository profiles() {
            return new StubPlayerProfileRepository() {
                public Optional<PlayerNickname> loadNickname(UUID uuid) {
                    assertEquals(nickname.playerUuid(), uuid);
                    nicknameReads++;
                    return Optional.of(nickname);
                }

                public NicknameChangeResult changeNickname(NicknameChange change) {
                    changes.add(change);
                    if (status == NicknameChangeResult.Status.CHANGED) {
                        nickname = new PlayerNickname(change.playerUuid(), change.nickname(), change.expectedRevision() + 1);
                    }
                    return new NicknameChangeResult(status, status == NicknameChangeResult.Status.NOT_FOUND ? null : nickname);
                }
            };
        }

        public LoadedPlayer loadPlayer(UUID uuid) {
            return new LoadedPlayer(Optional.empty(), AuthorizationSnapshot.initial(uuid));
        }

        public Map<UUID, AuthorizationSnapshot> loadAuthorizations(List<UUID> uuids) {
            Map<UUID, AuthorizationSnapshot> result = new HashMap<>();
            uuids.forEach(uuid -> result.put(uuid, AuthorizationSnapshot.initial(uuid)));
            return result;
        }

        public RoleChangeResult changeRole(RoleChangeRequest request) {
            throw new AssertionError("Nickname does not change roles");
        }

        public void appendCommandAudit(CommandAuditEvent event) {
            throw new AssertionError("No management invocation");
        }

        public Optional<PlayerProfile> findPlayer(UUID uuid) {
            throw new AssertionError("Use the shared resolver");
        }

        public PlayerTargetResolution resolveTarget(PlayerTarget reference) {
            lookups++;
            if (reference.equals(new PlayerTarget.ByName("Duplicate"))) {
                return PlayerTargetResolution.ambiguous();
            }
            if (reference.equals(new PlayerTarget.ByName("Aeron")) || reference.equals(new PlayerTarget.ByName("AccountName"))
                    || reference.equals(new PlayerTarget.ByUuid(target))) {
                return PlayerTargetResolution.found(new PublicPlayerProfile(
                        new PlayerProfile(target, "AccountName", Instant.EPOCH, Instant.EPOCH), Role.PLAYER, Optional.of("Aeron")));
            }
            return PlayerTargetResolution.missing();
        }

        public List<String> suggestTargets(String prefix) {
            return List.of();
        }

        public Optional<PlayerSession> findSession(UUID uuid) {
            return Optional.empty();
        }

        public PlayerProfile join(SessionStart start) {
            return new PlayerProfile(start.playerUuid(), start.username(), start.joinedAt(), start.joinedAt());
        }

        public boolean endSession(UUID uuid, Instant at, SessionEnd reason) {
            return true;
        }

        public int recoverInterrupted(Instant at) {
            return 0;
        }

        public void checkOwnership() {
        }

        public void close() {
        }
    }
}
