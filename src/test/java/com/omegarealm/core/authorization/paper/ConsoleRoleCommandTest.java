package com.omegarealm.core.authorization.paper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.omegarealm.core.RuntimeState;
import com.omegarealm.core.authorization.AuthorizationActor;
import com.omegarealm.core.authorization.AuthorizationSnapshot;
import com.omegarealm.core.authorization.RoleChangeRequest;
import com.omegarealm.core.authorization.RoleChangeResult;
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
class ConsoleRoleCommandTest {

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
    void requiresReasonAndCanonicalUuidBeforeAcceptingAnyWork() throws Exception {
        try (Harness harness = new Harness()) {
            CommandSourceStack source = source(harness.console, null);
            assertThrows(CommandSyntaxException.class,
                    () -> harness.dispatcher.execute("omega role set " + harness.target + " ADMIN", source));
            assertEquals(0, harness.dispatcher.execute("omega role set 1-1-1-1-1 ADMIN Staff appointment", source));
            assertEquals(0, harness.dispatcher.execute("omega role set " + harness.target + " UNKNOWN Staff appointment", source));
            assertEquals(0, harness.repository.calls);
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

    private static final class Harness implements AutoCloseable {
        private final UUID target = UUID.randomUUID();
        private final String command = "omega role set " + target + " ADMIN Staff appointment";
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final Queue<Runnable> replies = new ConcurrentLinkedQueue<>();
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
            dispatcher.getRoot().addChild(new ConsoleRoleCommand(() -> console, () -> RuntimeState.READY,
                    () -> service, scheduler, logger, () -> fail("Unexpected scheduling failure")).node());
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
            return new RoleChangeResult(RoleChangeResult.Status.CHANGED,
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
