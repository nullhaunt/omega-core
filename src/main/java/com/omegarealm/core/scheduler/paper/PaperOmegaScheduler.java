package com.omegarealm.core.scheduler.paper;

import com.omegarealm.core.scheduler.OmegaScheduler;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Owns Paper task handles, never Paper's scheduler threads or the runtime I/O worker.
 */
public final class PaperOmegaScheduler implements OmegaScheduler {

    private final Object ownership = new Object();
    private final Set<Registration> tasks = new HashSet<>();
    private final Plugin plugin;
    private final GlobalRegionScheduler global;
    private final AsyncScheduler async;
    private final Logger logger;
    private final Runnable criticalFailure;
    private boolean closed;
    private boolean failureReported;

    public PaperOmegaScheduler(Plugin plugin, GlobalRegionScheduler global, AsyncScheduler async,
                               Logger logger, Runnable criticalFailure) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.global = Objects.requireNonNull(global, "global");
        this.async = Objects.requireNonNull(async, "async");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.criticalFailure = Objects.requireNonNull(criticalFailure, "criticalFailure");
    }

    private static long positiveNanos(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("Scheduler durations must be positive.");
        }
        return duration.toNanos();
    }

    private static RejectedExecutionException unavailable() {
        // Paper exceptions may include plugin/platform details; expose no underlying chain.
        return new RejectedExecutionException("Omega scheduler is unavailable.");
    }

    @Override
    public void global(Runnable task) {
        Registration registration = register(task, false);
        try {
            registration.bind(global.run(plugin, ignored -> registration.run()));
        } catch (RuntimeException | LinkageError rejection) {
            registration.cancel();
            throw unavailable();
        }
    }

    @Override
    public Task repeatAsync(Duration initialDelay, Duration period, Runnable task) {
        long delayNanos = positiveNanos(initialDelay);
        long periodNanos = positiveNanos(period);
        Registration registration = register(task, true);
        try {
            registration.bind(async.runAtFixedRate(plugin, ignored -> registration.run(),
                    delayNanos, periodNanos, TimeUnit.NANOSECONDS));
        } catch (RuntimeException | LinkageError rejection) {
            registration.cancel();
            throw unavailable();
        }
        return registration;
    }

    @Override
    public void close() {
        ArrayList<Registration> owned;
        synchronized (ownership) {
            if (closed) {
                return;
            }
            closed = true;
            owned = new ArrayList<>(tasks);
            tasks.clear();
        }
        for (Registration registration : owned) {
            registration.cancel();
        }
    }

    private Registration register(Runnable task, boolean repeating) {
        Objects.requireNonNull(task, "task");
        synchronized (ownership) {
            if (closed) {
                throw unavailable();
            }
            Registration registration = new Registration(task, repeating);
            tasks.add(registration);
            return registration;
        }
    }

    private void cancelNative(ScheduledTask task) {
        if (task == null) {
            return;
        }
        try {
            task.cancel();
        } catch (RuntimeException | LinkageError failure) {
            reportFailure("Omega scheduling cancellation failed; requesting server shutdown.");
        }
    }

    private void reportFailure(String message) {
        synchronized (ownership) {
            if (failureReported) {
                return;
            }
            failureReported = true;
        }
        close();
        logger.severe(message);
        criticalFailure.run();
    }

    private final class Registration implements Task {
        private final Runnable callback;
        private final boolean repeating;
        private ScheduledTask nativeTask;
        private boolean running;
        private boolean finished;
        private boolean cancelled;

        private Registration(Runnable callback, boolean repeating) {
            this.callback = callback;
            this.repeating = repeating;
        }

        private void bind(ScheduledTask accepted) {
            Objects.requireNonNull(accepted, "accepted task");
            boolean discard;
            synchronized (ownership) {
                discard = closed || cancelled;
                if (!discard && !finished) {
                    nativeTask = accepted;
                }
            }
            // A scheduler can return its handle after callback completion or shutdown.
            // Never publish a resource into a cancelled registration.
            if (discard) {
                cancelNative(accepted);
            }
        }

        private void run() {
            synchronized (ownership) {
                if (closed || cancelled || finished || running) {
                    return;
                }
                running = true;
            }
            try {
                callback.run();
            } catch (RuntimeException | LinkageError failure) {
                reportFailure("Omega scheduled callback failed; requesting server shutdown.");
            } finally {
                synchronized (ownership) {
                    running = false;
                    if (!repeating) {
                        finished = true;
                        nativeTask = null;
                        tasks.remove(this);
                    }
                }
            }
        }

        @Override
        public void cancel() {
            ScheduledTask owned;
            synchronized (ownership) {
                if (cancelled || finished) {
                    return;
                }
                cancelled = true;
                tasks.remove(this);
                owned = nativeTask;
                nativeTask = null;
            }
            cancelNative(owned);
        }

        @Override
        public boolean isCancelled() {
            synchronized (ownership) {
                return closed || cancelled;
            }
        }
    }
}
