package com.omegarealm.core.scheduler;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;

/**
 * Platform dispatch for existing application work. This is not a database executor and
 * does not grant access to arbitrary entities or regions from a global callback.
 */
public interface OmegaScheduler extends AutoCloseable {

    /**
     * Dispatch once on Paper's global context. Never executes inline on the caller.
     *
     * @throws RejectedExecutionException if dispatch is closed or the platform rejects it
     */
    void global(Runnable task);

    /**
     * Repeat work independent of Minecraft world/entity state on Paper's async context.
     * Both durations must be positive. Overlapping invocations of one task are skipped.
     *
     * @throws RejectedExecutionException if dispatch is closed or the platform rejects it
     */
    Task repeatAsync(Duration initialDelay, Duration period, Runnable task);

    /**
     * Idempotently rejects new work and cancels owned tasks without waiting for running
     * callbacks. A callback claimed before cancellation may finish; later callbacks cannot start.
     */
    @Override
    void close();

    interface Task {
        void cancel();

        boolean isCancelled();
    }
}
