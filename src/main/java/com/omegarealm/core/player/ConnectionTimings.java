package com.omegarealm.core.player;

import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Debug-only timings for the two owners of connection preparation. Each line describes
 * one stage without names, UUIDs, network addresses, or database exception details.
 */
public final class ConnectionTimings {

    private final Logger logger;
    private final boolean enabled;
    public ConnectionTimings(Logger logger, boolean enabled) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.enabled = enabled;
    }

    private static String milliseconds(long nanos) {
        return String.format(Locale.ROOT, "%.3f", Math.max(0L, nanos) / 1_000_000.0);
    }

    private static String label(Outcome outcome) {
        return Objects.requireNonNull(outcome, "outcome").name().toLowerCase(Locale.ROOT);
    }

    public boolean enabled() {
        return enabled;
    }

    public void preparation(long queueNanos, long databaseNanos, Outcome outcome) {
        if (!enabled) {
            return;
        }

        logger.info("Omega connection timing: stage=preparation, queue-ms=" + milliseconds(queueNanos)
                + ", database-ms=" + milliseconds(databaseNanos) + ", outcome=" + label(outcome) + ".");
    }

    public void configuration(long waitNanos, Outcome outcome) {
        if (!enabled) {
            return;
        }

        logger.info("Omega connection timing: stage=configuration, wait-ms=" + milliseconds(waitNanos)
                + ", outcome=" + label(outcome) + ".");
    }

    public enum Outcome {
        PREPARED, BUSY, DUPLICATE, UNAVAILABLE, TIMED_OUT, CANCELLED, EXPIRED, DISCARDED, FAILED
    }
}
