package com.omegarealm.core.command.paper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.omegarealm.core.RuntimeState;
import com.omegarealm.core.authorization.*;
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

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class RoleCommandTest {

    private static <T> T proxy(Class<T> type, java.util.function.Function<String, Object> values) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, arguments) -> values.apply(method.getName())));
    }

    private static CommandSourceStack source(CommandSender sender, Player executor) {
        return proxy(CommandSourceStack.class, method -> switch (method) {
            case "getSender" -> sender;
            case "getExecutor" -> executor;
            default -> throw new AssertionError("Unexpected source access: " + method);
        });
    }

    @Test
    void rejectsPlayersRconAndDelegatedConsoleExecutionThroughTheParser() throws Exception {
        try (Harness harness = new Harness()) {
            CommandSender player = proxy(CommandSender.class, method -> {
                throw new AssertionError("OP/Bukkit permission checks must not establish authority");
            });
            RemoteConsoleCommandSender rcon = proxy(RemoteConsoleCommandSender.class, method -> null);
            Player executor = proxy(Player.class, method -> null);
            for (CommandSourceStack denied : List.of(source(player, null), source(rcon, null), source(harness.console, executor))) {
                assertThrows(CommandSyntaxException.class, () -> harness.dispatcher.execute(harness.command, denied));
                assertFalse(harness.dispatcher.getRoot().getChild("role").canUse(denied));
            }
            assertEquals(0, harness.repository.calls);
        }
    }

    @Test
    void incompleteCommandsExplainUsageWithoutAcceptingWork() throws Exception {
        try (Harness harness = new Harness()) {
            CommandSourceStack source = source(harness.console, null);
            for (String command : List.of("role", "role set",
                    "role set " + harness.target, "role set " + harness.target + " ADMIN")) {
                harness.messages.clear();
                assertEquals(0, harness.dispatcher.execute(command, source));
                assertEquals(List.of("Ω | Usage: role set <UUID> <PLAYER|MODERATOR|ADMIN|OWNER> <reason>."),
                        harness.messages);
            }
            assertEquals(0, harness.repository.calls);
        }
    }

    @Test
    void invalidArgumentsExplainWhichInputNeedsCorrection() throws Exception {
        try (Harness harness = new Harness()) {
            CommandSourceStack source = source(harness.console, null);
            assertEquals(0, harness.dispatcher.execute("role set 1-1-1-1-1 ADMIN Staff appointment", source));
            assertEquals(List.of("Ω | Use a canonical UUID (xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx)."), harness.messages);
            harness.messages.clear();
            assertEquals(0, harness.dispatcher.execute("role set " + harness.target + " UNKNOWN Staff appointment", source));
            assertEquals(List.of("Ω | Unknown role. Available roles: PLAYER|MODERATOR|ADMIN|OWNER."), harness.messages);
            harness.messages.clear();
            for (String reason : List.of("x".repeat(513), "Staff\nappointment")) {
                assertEquals(0, harness.dispatcher.execute("role set " + harness.target + " ADMIN " + reason, source));
                assertEquals(List.of("Ω | A reason of 1-512 characters without control characters is required."), harness.messages);
                harness.messages.clear();
            }
            assertEquals(0, harness.repository.calls);
        }
    }

    @Test
    void selfChangesExplainConsoleRequirementWithoutSubmittingWork() throws Exception {
        try (Harness harness = new Harness()) {
            Player player = harness.player();
            UUID uuid = UUID.randomUUID();
            CommandSourceStack source = source(player, player);
            for (Role actorRole : List.of(Role.ADMIN, Role.OWNER)) {
                harness.authorities.put(player, new AuthorizationSnapshot(uuid, actorRole, 1));
                for (Role requested : Role.values()) {
                    harness.messages.clear();
                    assertEquals(0, harness.dispatcher.execute("role set " + uuid + " " + requested + " Self change", source));
                    assertEquals(List.of("Ω | You cannot change your own role in-game. Use the local console."), harness.messages);
                }
            }
            assertEquals(0, harness.repository.calls);
        }
    }

    @Test
    void staffUsageAndValidationListOnlyAssignableRoles() throws Exception {
        try (Harness harness = new Harness()) {
            Player player = harness.player();
            CommandSourceStack source = source(player, player);
            harness.authorities.put(player, new AuthorizationSnapshot(UUID.randomUUID(), Role.ADMIN, 1));
            assertEquals(0, harness.dispatcher.execute("role set", source));
            assertEquals(List.of("Ω | Usage: /role set <UUID> <PLAYER|MODERATOR> <reason>."), harness.messages);
            harness.messages.clear();
            assertEquals(0, harness.dispatcher.execute("role set " + harness.target + " UNKNOWN Staff appointment", source));
            assertEquals(List.of("Ω | Unknown role. Available roles: PLAYER|MODERATOR."), harness.messages);
            harness.messages.clear();
            assertEquals(0, harness.dispatcher.execute(harness.command, source));
            assertEquals(List.of("Ω | Your role can assign PLAYER|MODERATOR. Only the local console can assign OWNER."), harness.messages);
            assertEquals(0, harness.repository.calls);
        }
    }

    @Test
    void storedPolicyDenialRepliesClearlyOnPlayerContextAfterCompletion() throws Exception {
        try (Harness harness = new Harness()) {
            Player player = harness.player();
            harness.authorities.put(player, new AuthorizationSnapshot(UUID.randomUUID(), Role.OWNER, 1));
            harness.repository.resultStatus = RoleChangeResult.Status.DENIED;
            assertEquals(1, harness.dispatcher.execute(harness.command, source(player, player)));
            assertTrue(harness.repository.entered.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("Ω | Role change queued."), harness.messages);
            assertTrue(harness.playerReplies.isEmpty());
            harness.repository.release.countDown();
            harness.worker.submit(() -> {
            }).get(2, TimeUnit.SECONDS);
            assertTrue(harness.replies.isEmpty());
            assertEquals(List.of("Ω | Role change queued."), harness.messages);
            harness.playerReplies.remove().run();
            assertEquals(List.of("Ω | Role change queued.",
                            "Ω | Role change denied: you cannot manage this target or role with your current authority. No role change was made."),
                    harness.messages);
        }
    }

    @Test
    void acceptsWithoutWaitingAndRepliesOnSchedulerOnlyAfterCommit() throws Exception {
        try (Harness harness = new Harness()) {
            assertEquals(1, harness.dispatcher.execute(harness.command, source(harness.console, null)));
            assertTrue(harness.repository.entered.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("Ω | Role change queued."), harness.messages);
            assertTrue(harness.replies.isEmpty());
            harness.repository.release.countDown();
            harness.worker.submit(() -> {
            }).get(2, TimeUnit.SECONDS);
            assertEquals(List.of("Ω | Role change queued."), harness.messages);
            assertEquals(1, harness.replies.size());
            harness.replies.remove().run();
            assertTrue(harness.messages.get(1).contains("Role changed:"));
            assertTrue(harness.messages.get(1).contains("revision=1"));
            assertFalse(harness.messages.get(1).contains("Staff appointment"));
            assertEquals("Staff appointment", harness.repository.request.reason());
            assertInstanceOf(AuthorizationActor.LocalConsole.class, harness.repository.request.actor());
            assertTrue(harness.auditLogs.stream().anyMatch(log -> log.contains("category=MANAGEMENT")
                    && log.contains("command=role.set") && log.contains("outcome=CHANGED")
                    && log.contains("mutation_audit=")));
            assertTrue(harness.auditLogs.stream().noneMatch(log -> log.contains("Staff appointment")));
        }
    }

    @Test
    void staffCommandsUseExactFreshAuthorityAndFilterRoleSuggestions() throws Exception {
        try (Harness harness = new Harness()) {
            Player player = harness.player();
            UUID uuid = UUID.randomUUID();
            var source = source(player, player);
            harness.authorities.put(player, new AuthorizationSnapshot(uuid, Role.ADMIN, 3));
            var suggestions = harness.dispatcher.getCompletionSuggestions(
                    harness.dispatcher.parse("role set " + harness.target + " ", source)).get();
            assertEquals(Set.of("PLAYER", "MODERATOR"), suggestions.getList().stream()
                    .map(com.mojang.brigadier.suggestion.Suggestion::getText).collect(java.util.stream.Collectors.toSet()));
            assertEquals(0, harness.dispatcher.execute(harness.command, source));
            harness.authorities.put(player, new AuthorizationSnapshot(uuid, Role.OWNER, 4));
            assertEquals(1, harness.dispatcher.execute(harness.command, source));
            assertTrue(harness.repository.entered.await(2, TimeUnit.SECONDS));
            assertEquals(new AuthorizationActor.Player(uuid, Role.OWNER), harness.repository.request.actor());
            harness.repository.release.countDown();
            harness.worker.submit(() -> {
            }).get(2, TimeUnit.SECONDS);
            assertTrue(harness.replies.isEmpty());
            assertEquals(1, harness.playerReplies.size());
        }
    }

    @Test
    void revocationBetweenParseAndExecutePreventsSubmittingRoleWork() throws Exception {
        try (Harness harness = new Harness()) {
            Player player = proxy(Player.class, method -> null);
            for (String command : List.of("role set", harness.command)) {
                harness.authorities.put(player, new AuthorizationSnapshot(UUID.randomUUID(), Role.OWNER, 1));
                var parsed = harness.dispatcher.parse(command, source(player, player));
                harness.authorities.remove(player);
                var denied = assertThrows(CommandSyntaxException.class, () -> harness.dispatcher.execute(parsed));
                assertEquals(command, denied.getInput());
                assertEquals(0, denied.getCursor());
            }
            assertTrue(harness.messages.isEmpty());
            assertEquals(0, harness.repository.calls);
        }
    }

    @Test
    void nonStaffCannotAccessUsageOrValidationFeedback() throws Exception {
        try (Harness harness = new Harness()) {
            Player player = harness.player();
            var source = source(player, player);
            for (Role role : List.of(Role.PLAYER, Role.MODERATOR)) {
                harness.authorities.put(player, new AuthorizationSnapshot(UUID.randomUUID(), role, 1));
                for (String command : List.of("role", "role set", harness.command)) {
                    var denied = assertThrows(CommandSyntaxException.class, () -> harness.dispatcher.execute(command, source));
                    assertEquals(command, denied.getInput());
                }
                assertTrue(harness.dispatcher.getCompletionSuggestions(harness.dispatcher.parse("rol", source)).get().isEmpty());
            }
            assertTrue(harness.messages.isEmpty());
            assertEquals(0, harness.repository.calls);
        }
    }

    private static final class Harness implements AutoCloseable {
        private final UUID target = UUID.randomUUID();
        private final String command = "role set " + target + " ADMIN Staff appointment";
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final List<String> auditLogs = new CopyOnWriteArrayList<>();
        private final Queue<Runnable> replies = new ConcurrentLinkedQueue<>();
        private final Queue<Runnable> playerReplies = new ConcurrentLinkedQueue<>();
        private final Map<Player, AuthorizationSnapshot> authorities = new IdentityHashMap<>();
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1);
        private final Repository repository = new Repository();
        private final PlayerService service;
        private final ConsoleCommandSender console;
        private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();

        private Harness() throws Exception {
            console = (ConsoleCommandSender) Proxy.newProxyInstance(ConsoleCommandSender.class.getClassLoader(),
                    new Class<?>[]{ConsoleCommandSender.class}, (object, method, arguments) -> {
                        if (method.getName().equals("sendPlainMessage")) {
                            messages.add((String) arguments[0]);
                            return null;
                        }
                        throw new AssertionError("Unexpected console access");
                    });
            Logger logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    auditLogs.add(record.getMessage());
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
            service = worker.submit(() -> {
                var initialized = new PlayerService(repository, worker, deadlines,
                        message -> fail(message), logger, Clock.systemUTC(), PlayerServiceLimits.DEFAULT);
                initialized.startOnWorker();
                return initialized;
            }).get(2, TimeUnit.SECONDS);
            OmegaScheduler scheduler = new OmegaScheduler() {
                @Override
                public void global(Runnable task) {
                    replies.add(task);
                }

                @Override
                public Task repeatAsync(Duration delay, Duration period, Runnable task) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void close() {
                }
            };
            CommandAccess access = new CommandAccess(() -> console, () -> RuntimeState.READY,
                    player -> Optional.ofNullable(authorities.get(player)));
            CommandAudit audit = new CommandAudit(logger, "unit-server");
            dispatcher.getRoot().addChild(new RoleCommand(access, () -> service, scheduler,
                    audit, logger, () -> fail("Unexpected scheduling failure"),
                    (player, reply) -> playerReplies.add(reply)).node());
        }

        private Player player() {
            return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (object, method, arguments) -> {
                        if (method.getName().equals("sendMessage") && arguments[0] instanceof Component component) {
                            messages.add(PlainTextComponentSerializer.plainText().serialize(component));
                            return null;
                        }
                        throw new AssertionError("Unexpected player access: " + method.getName());
                    });
        }

        @Override
        public void close() throws Exception {
            repository.release.countDown();
            service.requestStop(Instant.now());
            try {
                worker.submit(service::closeOnWorker).get(3, TimeUnit.SECONDS);
            } finally {
                worker.shutdown();
                deadlines.shutdownNow();
                assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
                assertTrue(deadlines.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }

    private static final class Repository implements PlayerSessionRepository {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile int calls;
        private RoleChangeRequest request;
        private RoleChangeResult.Status resultStatus = RoleChangeResult.Status.CHANGED;

        @Override
        public RoleChangeResult changeRole(RoleChangeRequest request) throws PersistenceException {
            calls++;
            this.request = request;
            entered.countDown();
            try {
                assertTrue(release.await(3, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            if (resultStatus == RoleChangeResult.Status.DENIED) {
                return new RoleChangeResult(resultStatus, null, null);
            }
            return new RoleChangeResult(resultStatus,
                    new AuthorizationSnapshot(request.targetUuid(), request.role(), 1), UUID.randomUUID());
        }

        @Override
        public int recoverInterrupted(Instant at) {
            return 0;
        }

        @Override
        public void checkOwnership() {
        }

        @Override
        public void close() {
        }

        @Override
        public Map<UUID, AuthorizationSnapshot> loadAuthorizations(List<UUID> ids) {
            return Map.of();
        }

        @Override
        public LoadedPlayer loadPlayer(UUID uuid) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PlayerProfile> findPlayer(UUID uuid) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PlayerSession> findSession(UUID uuid) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PlayerProfile join(SessionStart start) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean endSession(UUID id, Instant at, SessionEnd reason) {
            throw new UnsupportedOperationException();
        }
    }
}
