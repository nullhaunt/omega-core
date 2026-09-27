package com.omegarealm.core.authorization.paper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.omegarealm.core.RuntimeState;
import com.omegarealm.core.authorization.*;
import com.omegarealm.core.player.*;
import com.omegarealm.core.scheduler.OmegaScheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
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
                assertFalse(harness.dispatcher.getRoot().getChild("omega").canUse(denied));
            }
            assertEquals(0, harness.repository.calls);
        }
    }

    @Test
    void incompleteCommandsExplainUsageWithoutAcceptingWork() throws Exception {
        try (Harness harness = new Harness()) {
            CommandSourceStack source = source(harness.console, null);
            for (String command : List.of("omega", "omega role", "omega role set",
                    "omega role set " + harness.target, "omega role set " + harness.target + " ADMIN")) {
                harness.messages.clear();
                assertEquals(0, harness.dispatcher.execute(command, source));
                assertEquals(List.of("Usage: omega role set <UUID> <PLAYER|MODERATOR|ADMIN|OWNER> <reason of 1-512 characters>."),
                        harness.messages);
            }
            assertEquals(0, harness.repository.calls);
        }
    }

    @Test
    void invalidArgumentsExplainWhichInputNeedsCorrection() throws Exception {
        try (Harness harness = new Harness()) {
            CommandSourceStack source = source(harness.console, null);
            assertEquals(0, harness.dispatcher.execute("omega role set 1-1-1-1-1 ADMIN Staff appointment", source));
            assertEquals(List.of("Use a canonical UUID (xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx)."), harness.messages);
            harness.messages.clear();
            assertEquals(0, harness.dispatcher.execute("omega role set " + harness.target + " UNKNOWN Staff appointment", source));
            assertEquals(List.of("Unknown role. Available roles: PLAYER|MODERATOR|ADMIN|OWNER."), harness.messages);
            harness.messages.clear();
            for (String reason : List.of("x".repeat(513), "Staff\nappointment")) {
                assertEquals(0, harness.dispatcher.execute("omega role set " + harness.target + " ADMIN " + reason, source));
                assertEquals(List.of("A reason of 1-512 characters without control characters is required."), harness.messages);
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
                    assertEquals(0, harness.dispatcher.execute("omega role set " + uuid + " " + requested + " Self change", source));
                    assertEquals(List.of("You cannot change your own role in-game. Use the local console."), harness.messages);
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
            assertEquals(0, harness.dispatcher.execute("omega role set", source));
            assertEquals(List.of("Usage: /omega role set <UUID> <PLAYER|MODERATOR> <reason of 1-512 characters>."), harness.messages);
            harness.messages.clear();
            assertEquals(0, harness.dispatcher.execute("omega role set " + harness.target + " UNKNOWN Staff appointment", source));
            assertEquals(List.of("Unknown role. Available roles: PLAYER|MODERATOR."), harness.messages);
            harness.messages.clear();
            assertEquals(0, harness.dispatcher.execute(harness.command, source));
            assertEquals(List.of("Your role can assign PLAYER|MODERATOR. Only the local console can assign OWNER."), harness.messages);
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
            assertTrue(harness.messages.isEmpty());
            assertTrue(harness.playerReplies.isEmpty());
            harness.repository.release.countDown();
            harness.worker.submit(() -> {
            }).get(2, TimeUnit.SECONDS);
            assertTrue(harness.replies.isEmpty());
            assertTrue(harness.messages.isEmpty());
            harness.playerReplies.remove().run();
            assertEquals(List.of("Role change denied: you cannot manage this target or role with your current authority. No role change was made."),
                    harness.messages);
        }
    }

    @Test
    void acceptsWithoutWaitingAndRepliesOnSchedulerOnlyAfterCommit() throws Exception {
        try (Harness harness = new Harness()) {
            assertEquals(1, harness.dispatcher.execute(harness.command, source(harness.console, null)));
            assertTrue(harness.repository.entered.await(2, TimeUnit.SECONDS));
            assertTrue(harness.messages.isEmpty());
            assertTrue(harness.replies.isEmpty());
            harness.repository.release.countDown();
            harness.worker.submit(() -> {
            }).get(2, TimeUnit.SECONDS);
            assertTrue(harness.messages.isEmpty());
            assertEquals(1, harness.replies.size());
            harness.replies.remove().run();
            assertTrue(harness.messages.getFirst().contains("Role changed:"));
            assertTrue(harness.messages.getFirst().contains("revision=1"));
            assertFalse(harness.messages.getFirst().contains("Staff appointment"));
            assertEquals("Staff appointment", harness.repository.request.reason());
            assertInstanceOf(AuthorizationActor.LocalConsole.class, harness.repository.request.actor());
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
                    harness.dispatcher.parse("omega role set " + harness.target + " ", source)).get();
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
            for (String command : List.of("omega role set", harness.command)) {
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
                for (String command : List.of("omega", "omega role set", harness.command)) {
                    var denied = assertThrows(CommandSyntaxException.class, () -> harness.dispatcher.execute(command, source));
                    assertEquals(command, denied.getInput());
                }
                assertTrue(harness.dispatcher.getCompletionSuggestions(harness.dispatcher.parse("ome", source)).get().isEmpty());
            }
            assertTrue(harness.messages.isEmpty());
            assertEquals(0, harness.repository.calls);
        }
    }

    private static final class Harness implements AutoCloseable {
        private final UUID target = UUID.randomUUID();
        private final String command = "omega role set " + target + " ADMIN Staff appointment";
        private final List<String> messages = new CopyOnWriteArrayList<>();
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
            dispatcher.getRoot().addChild(new RoleCommand(() -> console, () -> RuntimeState.READY,
                    () -> service, scheduler, logger, () -> fail("Unexpected scheduling failure"),
                    player -> Optional.ofNullable(authorities.get(player)),
                    (player, reply) -> playerReplies.add(reply)).node());
        }

        private Player player() {
            return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (object, method, arguments) -> {
                        if (method.getName().equals("sendPlainMessage")) {
                            messages.add((String) arguments[0]);
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
