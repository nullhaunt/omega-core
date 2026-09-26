package com.omegarealm.core;

import com.omegarealm.core.config.OmegaConfig;
import com.omegarealm.core.database.Database;
import com.omegarealm.core.database.DatabaseBootstrap;
import com.omegarealm.core.database.DatabaseException;

import java.time.Duration;
import java.util.Objects;
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
    private final ExecutorService worker;
    private final ScheduledExecutorService startupClock;
    private final CompletableFuture<Void> readiness = new CompletableFuture<>();

    private volatile RuntimeState state = RuntimeState.STARTING;
    private boolean started;
    private Future<?> startupTask;
    private ScheduledFuture<?> deadline;
    private Database pendingDatabase;
    private Database database;

    /**
     * criticalFailure must be a thread-safe shutdown signal for the unavailable-scheduler fallback.
     */
    public OmegaRuntime(OmegaConfig config, Logger logger, DatabaseBootstrap bootstrap,
                        Executor lifecycle, Runnable criticalFailure) {
        this(config, logger, bootstrap, lifecycle, criticalFailure, Duration.ofSeconds(60));
    }

    OmegaRuntime(OmegaConfig config, Logger logger, DatabaseBootstrap bootstrap,
                 Executor lifecycle, Runnable criticalFailure, Duration startupTimeout) {
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.criticalFailure = Objects.requireNonNull(criticalFailure, "criticalFailure");
        this.startupTimeout = Objects.requireNonNull(startupTimeout, "startupTimeout");
        if (startupTimeout.isNegative() || startupTimeout.isZero()) {
            throw new IllegalArgumentException("startupTimeout must be positive");
        }

        worker = Executors.newSingleThreadExecutor(Thread.ofPlatform()
                .daemon(true).name("omega-bootstrap-" + config.server().id()).factory());
        startupClock = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                .daemon(true).name("omega-startup-deadline-" + config.server().id()).factory());
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
                    () -> fail("Database bootstrap exceeded its startup deadline.", true),
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

    public CompletionStage<Void> readiness() {
        return readiness.minimalCompletionStage();
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

            lifecycle.execute(this::publishReady);
        } catch (DatabaseException failure) {
            fail(failure.getMessage(), false);
        } catch (Exception | LinkageError failure) {
            // Third-party errors can contain connection details or SQL. Never forward them.
            fail("Unexpected database bootstrap failure; check the deployment and database configuration.", false);
        }
    }

    private void publishReady() {
        synchronized (ownership) {
            if (state != RuntimeState.STARTING) {
                return;
            }

            database = pendingDatabase;
            pendingDatabase = null;
            state = RuntimeState.READY;
            cancelDeadline();
        }

        logger.info("Omega Core READY: server=" + config.server().id()
                + ", environment=" + config.server().environment() + ", database=HEALTHY.");
        readiness.complete(null);
    }

    private void fail(String message, boolean interruptStartup) {
        synchronized (ownership) {
            if (state != RuntimeState.STARTING) {
                return;
            }

            state = RuntimeState.FAILED;
            cancelDeadline();
            if (interruptStartup && startupTask != null) {
                startupTask.cancel(true);
            }
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
    }

    /**
     * Closes admission immediately; resource cleanup is queued behind any in-flight bootstrap.
     */
    public void stop() {
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

            worker.execute(this::closeResources);
            worker.shutdown();
        }
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

        startupClock.shutdown();
    }

    private void closeResources() {
        Database pending;
        Database active;

        synchronized (ownership) {
            pending = pendingDatabase;
            active = database;
            pendingDatabase = null;
            database = null;
        }

        boolean clean = true;
        try {
            clean = closeDatabase(pending);
            if (active != pending) {
                clean = closeDatabase(active) && clean;
            }
        } finally {
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
