package com.omegarealm.core;

import com.omegarealm.core.authorization.AuthorizationSnapshot;
import com.omegarealm.core.config.DatabaseConfig;
import com.omegarealm.core.config.OmegaConfig;
import com.omegarealm.core.config.ServerConfig;
import com.omegarealm.core.database.Database;
import com.omegarealm.core.database.DatabaseBootstrap;
import com.omegarealm.core.database.DatabaseException;
import com.omegarealm.core.player.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class OmegaRuntimeTest {

    private final List<OmegaRuntime> runtimes = new ArrayList<>();
    private final BlockingQueue<Runnable> lifecycle = new LinkedBlockingQueue<>();
    private final AtomicInteger failures = new AtomicInteger();
    private final List<String> messages = new CopyOnWriteArrayList<>();

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignoredForLateCompletionTest) {
                interrupted = true;
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterEach
    void closeRuntimes() throws Exception {
        for (OmegaRuntime runtime : runtimes) {
            runtime.stop();
            assertTrue(runtime.awaitStopped(Duration.ofSeconds(3)), "Runtime workers must terminate");
        }
    }

    @Test
    void initializesOffCallerThreadAndPublishesReadyOnlyOnLifecycleContext() throws Exception {
        CountingDatabase database = new CountingDatabase();
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> initializer = new AtomicReference<>();
        OmegaRuntime runtime = runtime(() -> {
            initializer.set(Thread.currentThread());
            return database;
        });

        runtime.start();
        Runnable publish = nextCallback();

        assertNotSame(caller, initializer.get());
        assertEquals(RuntimeState.STARTING, runtime.state());
        assertEquals(RuntimeState.STARTING, runtime.diagnostics().state());
        assertTrue(runtime.diagnostics().players().isPresent());
        assertFalse(runtime.readiness().toCompletableFuture().isDone());

        publish.run();

        assertEquals(RuntimeState.READY, runtime.state());
        assertTrue(runtime.diagnostics().players().orElseThrow().running());
        runtime.readiness().toCompletableFuture().get(1, TimeUnit.SECONDS);
        runtime.stop();
        runtime.stop();
        assertTrue(runtime.awaitStopped(Duration.ofSeconds(2)));
        assertEquals(RuntimeState.STOPPED, runtime.state());
        assertEquals(RuntimeState.STOPPED, runtime.diagnostics().state());
        assertEquals(1, database.closes.get());
        assertNotSame(caller, database.closedOn.get());
        assertEquals(0, failures.get());
    }

    @Test
    void failureClosesAdmissionBeforeSchedulingServerShutdown() throws Exception {
        OmegaRuntime runtime = runtime(() -> {
            throw new DatabaseException("Database migration failed (SQLSTATE 42601).");
        });

        runtime.start();
        Runnable shutdown = nextCallback();

        assertEquals(RuntimeState.FAILED, runtime.state());
        assertEquals(0, failures.get());
        assertThrows(ExecutionException.class,
                () -> runtime.readiness().toCompletableFuture().get(1, TimeUnit.SECONDS));

        shutdown.run();

        assertEquals(1, failures.get());
        assertTrue(messages.stream().anyMatch(message -> message.contains("SQLSTATE 42601")));
    }

    @Test
    void shutdownBeforeReadinessHandoffClosesPendingDatabaseAndIgnoresLateCallback() throws Exception {
        CountingDatabase database = new CountingDatabase();
        OmegaRuntime runtime = runtime(() -> database);
        runtime.start();
        Runnable publish = nextCallback();

        runtime.stop();
        assertTrue(runtime.awaitStopped(Duration.ofSeconds(2)));
        publish.run();

        assertEquals(RuntimeState.STOPPED, runtime.state());
        assertEquals(1, database.closes.get());
        assertFalse(messages.stream().anyMatch(message -> message.contains(" READY:")));
    }

    @Test
    void shutdownDuringUninterruptibleInitializationClosesItsLateResult() throws Exception {
        CountingDatabase database = new CountingDatabase();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OmegaRuntime runtime = runtime(() -> {
            entered.countDown();
            awaitIgnoringInterrupt(release);
            return database;
        });

        try {
            runtime.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            runtime.stop();
            assertEquals(RuntimeState.STOPPING, runtime.state());
        } finally {
            release.countDown();
        }

        assertTrue(runtime.awaitStopped(Duration.ofSeconds(2)));
        assertEquals(1, database.closes.get());
        assertTrue(lifecycle.isEmpty());
        assertEquals(RuntimeState.STOPPED, runtime.state());
    }

    @Test
    void timeoutWinsOverAnAlreadyQueuedReadinessCallback() throws Exception {
        CountingDatabase database = new CountingDatabase();
        OmegaRuntime runtime = runtime(() -> database, Duration.ofMillis(100));
        runtime.start();
        Runnable publish = nextCallback();
        Runnable shutdown = nextCallback();

        assertEquals(RuntimeState.FAILED, runtime.state());
        publish.run();
        assertEquals(RuntimeState.FAILED, runtime.state());
        shutdown.run();
        runtime.stop();

        assertTrue(runtime.awaitStopped(Duration.ofSeconds(2)));
        assertEquals(1, database.closes.get());
        assertEquals(1, failures.get());
        assertFalse(messages.stream().anyMatch(message -> message.contains(" READY:")));
    }

    @Test
    void timeoutInterruptsInitializationAndDiscardsItsLateResult() throws Exception {
        CountingDatabase database = new CountingDatabase();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OmegaRuntime runtime = runtime(() -> {
            entered.countDown();
            awaitIgnoringInterrupt(release);
            return database;
        }, Duration.ofMillis(200));

        try {
            runtime.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            nextCallback().run();
            assertEquals(RuntimeState.FAILED, runtime.state());
            assertEquals(1, failures.get());
            runtime.stop();
        } finally {
            release.countDown();
        }

        assertTrue(runtime.awaitStopped(Duration.ofSeconds(2)));
        assertEquals(1, database.closes.get());
        assertTrue(lifecycle.isEmpty());
    }

    @Test
    void hidesUnexpectedLibraryExceptionValues() throws Exception {
        OmegaRuntime runtime = runtime(() -> {
            throw new IllegalStateException("DO_NOT_LOG_THIS");
        });
        runtime.start();
        nextCallback().run();

        assertEquals(RuntimeState.FAILED, runtime.state());
        assertFalse(messages.stream().anyMatch(message -> message.contains("DO_NOT_LOG_THIS")));
        ExecutionException error = assertThrows(ExecutionException.class,
                () -> runtime.readiness().toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertFalse(error.getCause().getMessage().contains("DO_NOT_LOG_THIS"));
        assertNull(error.getCause().getCause());
    }

    @Test
    void stoppingAnUnstartedRuntimeIsSafeAndPreventsLaterStart() throws Exception {
        OmegaRuntime runtime = runtime(() -> fail("Bootstrap must not run"));
        runtime.stop();
        runtime.stop();

        assertTrue(runtime.awaitStopped(Duration.ofSeconds(2)));
        assertThrows(IllegalStateException.class, runtime::start);
    }

    @Test
    void unavailableLifecycleSchedulerStillClosesResourcesAndSignalsShutdown() throws Exception {
        CountingDatabase database = new CountingDatabase();
        OmegaConfig config = new OmegaConfig(new ServerConfig("test", "development"),
                new DatabaseConfig("localhost", 5432, "omega", "omega", "TEST_PASSWORD", 5), false);
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        OmegaRuntime runtime = new OmegaRuntime(config, logger, () -> database,
                task -> {
                    throw new RejectedExecutionException("DO_NOT_LOG_THIS");
                }, failures::incrementAndGet, Duration.ofSeconds(5),
                (owned, id) -> new TestRepository(), PlayerServiceLimits.DEFAULT);
        runtimes.add(runtime);

        runtime.start();
        assertThrows(ExecutionException.class,
                () -> runtime.readiness().toCompletableFuture().get(2, TimeUnit.SECONDS));
        assertTrue(runtime.awaitStopped(Duration.ofSeconds(2)));

        assertEquals(1, database.closes.get());
        assertEquals(1, failures.get());
        assertEquals(RuntimeState.STOPPED, runtime.state());
    }

    @Test
    void readinessWaitsForRecoveryAndStopDiscardsLateRecoveryCompletion() throws Exception {
        CountingDatabase database = new CountingDatabase();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TestRepository repository = new TestRepository() {
            @Override
            public int recoverInterrupted(Instant at) {
                entered.countDown();
                awaitIgnoringInterrupt(release);
                return 1;
            }

            @Override
            public void close() {
                database.events.add("repository");
            }
        };
        OmegaRuntime runtime = runtime(() -> database, Duration.ofSeconds(5),
                (owned, id) -> repository, PlayerServiceLimits.DEFAULT);
        try {
            runtime.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(RuntimeState.STARTING, runtime.state());
            assertFalse(runtime.readiness().toCompletableFuture().isDone());
            assertThrows(IllegalStateException.class, runtime::players);
            runtime.stop();
        } finally {
            release.countDown();
        }
        assertTrue(runtime.awaitStopped(Duration.ofSeconds(2)));
        assertEquals(List.of("repository", "database"), database.events);
        assertTrue(lifecycle.isEmpty());
        assertFalse(messages.stream().anyMatch(message -> message.contains(" READY:")));
    }

    @Test
    void lateRepositoryAcquisitionClosesBeforePoolAndCannotPublishReady() throws Exception {
        CountingDatabase database = new CountingDatabase();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TestRepository repository = new TestRepository() {
            @Override
            public void close() {
                database.events.add("repository");
            }
        };
        OmegaRuntime runtime = runtime(() -> database, Duration.ofSeconds(5), (owned, id) -> {
            entered.countDown();
            awaitIgnoringInterrupt(release);
            return repository;
        }, PlayerServiceLimits.DEFAULT);
        try {
            runtime.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            runtime.stop();
        } finally {
            release.countDown();
        }
        assertTrue(runtime.awaitStopped(Duration.ofSeconds(2)));
        assertEquals(List.of("repository", "database"), database.events);
        assertTrue(lifecycle.isEmpty());
    }

    @Test
    void recoveryFailurePreventsReadinessAndClosesRepositoryBeforePool() throws Exception {
        CountingDatabase database = new CountingDatabase();
        TestRepository repository = new TestRepository() {
            @Override
            public int recoverInterrupted(Instant at) {
                throw new IllegalStateException("DO_NOT_LOG_THIS_SECRET");
            }

            @Override
            public void close() {
                database.events.add("repository");
            }
        };
        OmegaRuntime runtime = runtime(() -> database, Duration.ofSeconds(5),
                (owned, id) -> repository, PlayerServiceLimits.DEFAULT);
        runtime.start();
        nextCallback().run();
        assertEquals(RuntimeState.FAILED, runtime.state());
        assertEquals(1, failures.get());
        runtime.stop();
        assertTrue(runtime.awaitStopped(Duration.ofSeconds(2)));
        assertEquals(List.of("repository", "database"), database.events);
        assertFalse(messages.stream().anyMatch(message -> message.contains("DO_NOT_LOG")));
    }

    @Test
    void criticalPlayerFailureAfterReadinessClosesAdmissionBeforeShutdownSignal() throws Exception {
        CountingDatabase database = new CountingDatabase();
        TestRepository repository = new TestRepository() {
            @Override
            public Optional<PlayerProfile> findPlayer(UUID uuid) {
                throw new IllegalStateException("DO_NOT_LOG_THIS_SECRET");
            }
        };
        OmegaRuntime runtime = runtime(() -> database, Duration.ofSeconds(5),
                (owned, id) -> repository, PlayerServiceLimits.DEFAULT);
        runtime.start();
        nextCallback().run();
        assertEquals(RuntimeState.READY, runtime.state());
        assertThrows(ExecutionException.class, () -> runtime.players()
                .prepare(new PlayerAttempt(UUID.randomUUID(), new PlayerIdentity(UUID.randomUUID(), "Player")))
                .toCompletableFuture().get(2, TimeUnit.SECONDS));
        Runnable shutdown = nextCallback();
        assertEquals(RuntimeState.FAILED, runtime.state());
        assertThrows(IllegalStateException.class, runtime::players);
        assertEquals(0, failures.get());
        shutdown.run();
        assertEquals(1, failures.get());
        assertFalse(messages.stream().anyMatch(message -> message.contains("DO_NOT_LOG")));
    }

    @Test
    void idleOwnershipFailureStopsReadyRuntimeAndStartupTimerStaysCancelled() throws Exception {
        CountingDatabase database = new CountingDatabase();
        AtomicInteger checks = new AtomicInteger();
        TestRepository repository = new TestRepository() {
            @Override
            public void checkOwnership() {
                checks.incrementAndGet();
                throw new IllegalStateException("DO_NOT_LOG_THIS_SECRET");
            }
        };
        PlayerServiceLimits limits = new PlayerServiceLimits(64, 256, Duration.ofSeconds(3),
                Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofMillis(100),
                Duration.ofSeconds(3), Duration.ofSeconds(3));
        OmegaRuntime runtime = runtime(() -> database, Duration.ofSeconds(5),
                (owned, id) -> repository, limits);
        runtime.start();
        nextCallback().run();
        assertEquals(RuntimeState.READY, runtime.state());
        nextCallback().run();
        assertEquals(RuntimeState.FAILED, runtime.state());
        assertEquals(1, checks.get());
        assertEquals(1, failures.get());
    }

    @Test
    void schedulingFailureAfterReadyClosesAdmissionAndRequestsShutdownOnce() throws Exception {
        CountingDatabase database = new CountingDatabase();
        OmegaRuntime runtime = runtime(() -> database);
        runtime.start();
        nextCallback().run();
        assertEquals(RuntimeState.READY, runtime.state());

        runtime.failScheduling();
        runtime.failScheduling();
        assertEquals(RuntimeState.FAILED, runtime.state());
        assertThrows(IllegalStateException.class, runtime::players);
        assertEquals(0, failures.get());
        nextCallback().run();
        assertEquals(1, failures.get());
        runtime.stop();
        assertTrue(runtime.awaitStopped(Duration.ofSeconds(2)));
        assertEquals(1, database.closes.get());
    }

    @Test
    void schedulerRejectionAfterReadyStillDrainsAndSignalsShutdown() throws Exception {
        CountingDatabase database = new CountingDatabase();
        AtomicInteger scheduling = new AtomicInteger();
        TestRepository repository = new TestRepository() {
            @Override
            public Optional<PlayerProfile> findPlayer(UUID uuid) {
                throw new IllegalStateException("DO_NOT_LOG_THIS_SECRET");
            }
        };
        OmegaConfig config = new OmegaConfig(new ServerConfig("test", "development"),
                new DatabaseConfig("localhost", 5432, "omega", "omega", "TEST_PASSWORD", 5), false);
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        OmegaRuntime runtime = new OmegaRuntime(config, logger, () -> database, task -> {
            if (scheduling.getAndIncrement() == 0) {
                lifecycle.add(task);
            } else {
                throw new RejectedExecutionException("DO_NOT_LOG_THIS_SECRET");
            }
        }, failures::incrementAndGet, Duration.ofSeconds(5), (owned, id) -> repository, PlayerServiceLimits.DEFAULT);
        runtimes.add(runtime);
        runtime.start();
        nextCallback().run();
        assertThrows(ExecutionException.class, () -> runtime.players()
                .prepare(new PlayerAttempt(UUID.randomUUID(), new PlayerIdentity(UUID.randomUUID(), "Player")))
                .toCompletableFuture().get(2, TimeUnit.SECONDS));
        assertTrue(runtime.awaitStopped(Duration.ofSeconds(2)));
        assertEquals(RuntimeState.STOPPED, runtime.state());
        assertEquals(1, failures.get());
        assertEquals(1, database.closes.get());
    }

    @Test
    void shutdownClosesLateCommittedJoinBeforeClosingPool() throws Exception {
        CountingDatabase database = new CountingDatabase();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TestRepository repository = new TestRepository() {
            @Override
            public PlayerProfile join(SessionStart start) {
                entered.countDown();
                awaitIgnoringInterrupt(release);
                return super.join(start);
            }

            @Override
            public boolean endSession(UUID id, Instant at, SessionEnd reason) {
                assertEquals(SessionEnd.SHUTDOWN, reason);
                database.events.add("session");
                return true;
            }

            @Override
            public void close() {
                database.events.add("repository");
            }
        };
        OmegaRuntime runtime = runtime(() -> database, Duration.ofSeconds(5),
                (owned, id) -> repository, PlayerServiceLimits.DEFAULT);
        runtime.start();
        nextCallback().run();
        var service = runtime.players();
        PlayerAttempt player = new PlayerAttempt(UUID.randomUUID(), new PlayerIdentity(UUID.randomUUID(), "Player"));
        service.prepare(player).toCompletableFuture().get(2, TimeUnit.SECONDS);
        var activation = service.activate(player.token(), Instant.now().minusSeconds(1));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            runtime.stop();
            assertThrows(ExecutionException.class,
                    () -> activation.toCompletableFuture().get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        assertTrue(runtime.awaitStopped(Duration.ofSeconds(2)));
        assertEquals(List.of("session", "repository", "database"), database.events);
        assertEquals(0, failures.get());
    }

    private OmegaRuntime runtime(DatabaseBootstrap bootstrap) {
        return runtime(bootstrap, Duration.ofSeconds(5));
    }

    private OmegaRuntime runtime(DatabaseBootstrap bootstrap, Duration timeout) {
        return runtime(bootstrap, timeout, (owned, id) -> new TestRepository(), PlayerServiceLimits.DEFAULT);
    }

    private OmegaRuntime runtime(DatabaseBootstrap bootstrap, Duration timeout,
                                 PlayerRepositoryFactory repositoryFactory, PlayerServiceLimits limits) {
        OmegaConfig config = new OmegaConfig(new ServerConfig("test", "development"),
                new DatabaseConfig("localhost", 5432, "omega", "omega", "TEST_PASSWORD", 5), false);
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        OmegaRuntime runtime = new OmegaRuntime(config, logger, bootstrap, lifecycle::add,
                failures::incrementAndGet, timeout,
                repositoryFactory, limits);
        runtimes.add(runtime);
        return runtime;
    }

    private Runnable nextCallback() throws InterruptedException {
        Runnable callback = lifecycle.poll(3, TimeUnit.SECONDS);
        assertNotNull(callback, "Expected a lifecycle callback");
        return callback;
    }

    private static final class CountingDatabase implements Database {
        private final AtomicInteger closes = new AtomicInteger();
        private final AtomicReference<Thread> closedOn = new AtomicReference<>();
        private final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public Connection openConnection() {
            throw new UnsupportedOperationException("Lifecycle tests do not execute SQL");
        }

        @Override
        public void close() {
            events.add("database");
            closes.incrementAndGet();
            closedOn.set(Thread.currentThread());
        }
    }

    private static class TestRepository implements PlayerSessionRepository {

        @Override
        public PlayerTargetResolution resolveTarget(PlayerTarget target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void appendCommandAudit(com.omegarealm.core.command.CommandAuditEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LoadedPlayer loadPlayer(UUID uuid) {
            return new LoadedPlayer(findPlayer(uuid), AuthorizationSnapshot.initial(uuid));
        }

        @Override
        public java.util.Map<UUID, AuthorizationSnapshot> loadAuthorizations(java.util.List<UUID> uuids) {
            return uuids.stream().collect(java.util.stream.Collectors.toMap(uuid -> uuid, AuthorizationSnapshot::initial));
        }

        @Override
        public com.omegarealm.core.authorization.RoleChangeResult changeRole(
                com.omegarealm.core.authorization.RoleChangeRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PlayerProfile> findPlayer(UUID uuid) {
            return Optional.empty();
        }

        @Override
        public Optional<PlayerSession> findSession(UUID id) {
            return Optional.empty();
        }

        @Override
        public PlayerProfile join(SessionStart start) {
            return new PlayerProfile(start.playerUuid(), start.username(), start.joinedAt(), start.joinedAt());
        }

        @Override
        public boolean endSession(UUID id, Instant at, SessionEnd reason) {
            return true;
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
    }
}
