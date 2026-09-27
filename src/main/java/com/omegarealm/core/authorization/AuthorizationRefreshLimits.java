package com.omegarealm.core.authorization;

import java.time.Duration;
import java.util.Objects;

/**
 * Fixed implementation bounds; neither configuration reload nor another resource owner.
 */
public record AuthorizationRefreshLimits(Duration interval, Duration freshness, int batchSize) {

    public static final int MAX_BATCH_SIZE = 64;
    public static final AuthorizationRefreshLimits DEFAULT =
            new AuthorizationRefreshLimits(Duration.ofSeconds(1), Duration.ofSeconds(5), MAX_BATCH_SIZE);

    public AuthorizationRefreshLimits {
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(freshness, "freshness");
        if (interval.isNegative() || interval.isZero() || freshness.compareTo(interval) <= 0
                || batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Invalid authorization refresh bounds.");
        }
        interval.toNanos();
        freshness.toNanos();
    }
}
