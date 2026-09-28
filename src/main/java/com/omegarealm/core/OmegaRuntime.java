package com.omegarealm.core;

import com.omegarealm.core.config.OmegaConfig;
import com.omegarealm.core.database.Database;
import com.omegarealm.core.database.DatabaseBootstrap;
import com.omegarealm.core.database.DatabaseException;
import com.omegarealm.core.diagnostics.RuntimeDiagnostics;
import com.omegarealm.core.player.*;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Owns bootstrap and resource lifetime. Database I/O and closure never hold the state monitor.
 */
public final class OmegaRuntime {

    private final Object ownership = new Object();
    private final OmegaConfig config;
    private final Logger logger;
    private final DatabaseBootstrap bootstrap;
    private final Executor lifecycle;
    private final Runnable criticalFailure;
    private final Duration startupTimeout;
    private final PlayerRepositoryFactory repositories;
    private final PlayerServiceLimits playerLimits;
    private final ExecutorService worker;
    private final ScheduledExecutorService startupClock;
    private final CompletableFuture<Void> readiness = new CompletableFuture<>();
    private final long createdAtNanos = System.nanoTime();

    private volatile RuntimeState state = RuntimeState.STARTING;
    private boolean started;
    private Future<?> startupTask;
    private ScheduledFuture<?> deadline;
    private Database pendingDatabase;
    private Database database;
    private PlayerService playerService;

    /**
     * criticalFailure must be a thread-safe shutdown signal for the unavailable-scheduler fallback.
     */
    public OmegaRuntime(OmegaConfig config, Logger logger, DatabaseBootstrap bootstrap,
                        Executor lifecycle, Runnable criticalFailure) {
        this(config, logger, bootstrap, lifecycle, criticalFailure, Duration.ofSeconds(60));
    }

    OmegaRuntime(OmegaConfig config, Logger logger, DatabaseBootstrap bootstrap,
                 Executor lifecycle, Runnable criticalFailure, Duration startupTimeout) {
        this(config, logger, bootstrap, lifecycle, criticalFailure, startupTimeout,
                PostgresPlayerSessionRepository::acquire, PlayerServiceLimits.DEFAULT);
    }

    OmegaRuntime(OmegaConfig config, Logger logger, DatabaseBootstrap bootstrap,
                 Executor lifecycle, Runnable criticalFailure, Duration startupTimeout,
                 PlayerRepositoryFactory repositories, PlayerServiceLimits playerLimits) {
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.criticalFailure = Objects.requireNonNull(criticalFailure, "criticalFailure");
        this.startupTimeout = Objects.requireNonNull(startupTimeout, "startupTimeout");
        this.repositories = Objects.requireNonNull(repositories, "repositories");
        this.playerLimits = Objects.requireNonNull(playerLimits, "playerLimits");
        if (startupTimeout.isNegative() || startupTimeout.isZero()) {
            throw new IllegalArgumentException("startupTimeout must be positive");
        }

        // Only startup, one mailbox pump, and shutdown can submit here. Player work has
        // its own bounded mailbox, so shutdown never competes with an unbounded task queue.
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(3), Thread.ofPlatform()
                .daemon(true).name("omega-io-" + config.server().id()).factory());
        ScheduledThreadPoolExecutor clock = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform()
                .daemon(true).name("omega-deadline-" + config.server().id()).factory());
        clock.setRemoveOnCancelPolicy(true);
        clock.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        clock.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        startupClock = clock;
    }

    /**
     * Returns immediately. Readiness is published on the supplied Paper lifecycle executor.
     */
    public void start() {
        synchronized (ownership) {
            if (started || state != RuntimeState.STARTING) {
                throw new IllegalStateException("Runtime startup is allowed exactly once");
            }

            started = true;
            logger.info("Omega Core STARTING: configuration validated; player admission is closed.");
            deadline = startupClock.schedule(
                    () -> fail("Application bootstrap exceeded its startup deadline.", true),
                    startupTimeout.toMillis(), TimeUnit.MILLISECONDS);
            startupTask = worker.submit(this::initialize);
        }
    }

    public OmegaConfig config() {
        return config;
    }

    public RuntimeState state() {
        return state;
    }

    /**
     * Capture runtime ownership first, then read the player service outside that monitor.
     */
    public RuntimeDiagnostics diagnostics() {
        RuntimeState observedState;
        PlayerService observedPlayers;
        synchronized (ownership) {
            observedState = state;
            observedPlayers = playerService;
        }

        return new RuntimeDiagnostics(observedState, config.server().id(),
                config.server().environment(),
                Duration.ofNanos(Math.max(0L, System.nanoTime() - createdAtNanos)),
                observedPlayers == null ? Optional.empty() : Optional.of(observedPlayers.diagnostics()));
    }

    public CompletionStage<Void> readiness() {
        return readiness.minimalCompletionStage();
    }

    /**
     * Future Paper adapters use this service without accessing the database resource.
     */
    public PlayerService players() {
        synchronized (ownership) {
            if (state != RuntimeState.READY) {
                throw new IllegalStateException("Player service is unavailable before runtime readiness.");
            }
            return playerService;
        }
    }

    /**
     * A platform invariant failure is as critical as an unavailable persistence service.
     */
    public void failPlayerHandling() {
        fail("Player connection handling is unavailable.", false);
    }

    public void failChatHandling() {
        fail("Public chat delivery is unavailable.", false);
    }

    /**
     * Platform callback failure closes admission even after startup has completed.
     */
    public void failScheduling() {
        fail("Application scheduling is unavailable.", false);
    }

    private void initialize() {
        try {
            Database initialized = Objects.requireNonNull(bootstrap.initialize(), "initialized database");
            boolean accepted;

            synchronized (ownership) {
                accepted = state == RuntimeState.STARTING;
                if (accepted) {
                    pendingDatabase = initialized;
                }
            }

            if (!accepted) {
                initialized.close();
                return;
            }

            PlayerService initializedPlayers = new PlayerService(
                    repositories.acquire(initialized, config.server().id()), worker, startupClock,
                    message -> fail(message, false), logger, Clock.systemUTC(), playerLimits, config.debug());
            synchronized (ownership) {
                accepted = state == RuntimeState.STARTING;
                if (accepted) {
                    playerService = initializedPlayers;
                }
            }
            if (!accepted) {
                initializedPlayers.closeOnWorker();
                return;
            }
            initializedPlayers.startOnWorker();
            synchronized (ownership) {
                if (state != RuntimeState.STARTING || !initializedPlayers.isRunning()) {
                    return;
                }
            }

            lifecycle.execute(this::publishReady);
        } catch (DatabaseException failure) {
            fail(failure.getMessage(), false);
        } catch (PersistenceException failure) {
            fail(failure.getMessage(), false);
        } catch (Exception | LinkageError failure) {
            // Third-party errors can contain connection details or SQL. Never forward them.
            fail("Unexpected application bootstrap failure; check the deployment and database configuration.", false);
        }
    }

    private void publishReady() {
        synchronized (ownership) {
            if (state != RuntimeState.STARTING || playerService == null || !playerService.isRunning()) {
                return;
            }

            database = pendingDatabase;
            pendingDatabase = null;
            state = RuntimeState.READY;
            cancelDeadline();
        }

        logger.info("Omega Core READY: server=" + config.server().id()
                + ", environment=" + config.server().environment() + ", database=HEALTHY, players=HEALTHY.");
        readiness.complete(null);
    }

    private void fail(String message, boolean interruptStartup) {
        PlayerService failingPlayers;
        synchronized (ownership) {
            if (state != RuntimeState.STARTING && state != RuntimeState.READY) {
                return;
            }

            state = RuntimeState.FAILED;
            cancelDeadline();
            if (interruptStartup && startupTask != null) {
                startupTask.cancel(true);
            }
            failingPlayers = playerService;
        }

        logger.severe("Omega Core FAILED: " + message);
        readiness.completeExceptionally(new IllegalStateException(message));

        try {
            lifecycle.execute(() -> {
                if (state == RuntimeState.FAILED) {
                    criticalFailure.run();
                }
            });
        } catch (RuntimeException unavailableScheduler) {
            // Plugin disable normally closes the scheduler and calls stop itself.
            logger.severe("Lifecycle scheduler unavailable; closing resources and requesting emergency server shutdown.");
            stop();
            criticalFailure.run();
        }
        if (failingPlayers != null) {
            failingPlayers.requestStop(Clock.systemUTC().instant());
        }
    }

    /**
     * Closes admission immediately; resource cleanup is queued behind any in-flight bootstrap.
     */
    public void stop() {
        PlayerService stoppingPlayers;
        synchronized (ownership) {
            if (state == RuntimeState.STOPPING || state == RuntimeState.STOPPED) {
                return;
            }

            state = RuntimeState.STOPPING;
            cancelDeadline();
            readiness.cancel(false);
            if (startupTask != null) {
                startupTask.cancel(true);
            }
            stoppingPlayers = playerService;
        }
        if (stoppingPlayers != null) {
            stoppingPlayers.requestStop(Clock.systemUTC().instant());
        }
        startupClock.shutdown();
        worker.execute(this::closeResources);
        worker.shutdown();
    }

    /**
     * Only for the shutdown boundary or tests; never wait during normal Minecraft ticking.
     */
    public boolean awaitStopped(Duration timeout) throws InterruptedException {
        long remaining = timeout.toNanos();
        long startedWaiting = System.nanoTime();
        if (!worker.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
            return false;
        }

        remaining = Math.max(0, remaining - (System.nanoTime() - startedWaiting));
        return startupClock.awaitTermination(remaining, TimeUnit.NANOSECONDS);
    }

    /**
     * Caller holds ownership. Cancelling a timer never waits for it.
     */
    private void cancelDeadline() {
        if (deadline != null) {
            deadline.cancel(false);
        }
    }

    private void closeResources() {
        Database pending;
        Database active;
        PlayerService players;

        synchronized (ownership) {
            pending = pendingDatabase;
            active = database;
            pendingDatabase = null;
            database = null;
            players = playerService;
            playerService = null;
        }

        boolean clean = true;
        try {
            if (players != null) {
                clean = players.closeOnWorker();
            }
        } catch (RuntimeException | LinkageError failure) {
            logger.severe("Player service cleanup failed; verify server ownership before restarting.");
            clean = false;
        } finally {
            clean = closeDatabase(pending) && clean;
            if (active != pending) {
                clean = closeDatabase(active) && clean;
            }
            state = RuntimeState.STOPPED;
            logger.info(clean ? "Omega Core STOPPED: owned resources closed."
                    : "Omega Core STOPPED: resource cleanup encountered errors.");
        }
    }

    private boolean closeDatabase(Database owned) {
        if (owned == null) {
            return true;
        }

        try {
            owned.close();
            return true;
        } catch (RuntimeException failure) {
            logger.severe("Database resource cleanup failed; check the server and database before restarting.");
            return false;
        }
    }
}
