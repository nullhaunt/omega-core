package com.omegarealm.core.player;

import java.time.Duration;
import java.util.Objects;

/**
 * Implementation limits, not hot-reloadable operator settings.
 */
public record PlayerServiceLimits(int pendingOperations, int trackedAttempts, Duration loadTimeout,
                                  Duration preparedTimeout, Duration mutationTimeout,
                                  Duration ownershipInterval, Duration ownershipTimeout,
                                  Duration cleanupTimeout) {

    public static final PlayerServiceLimits DEFAULT = new PlayerServiceLimits(64, 256,
            Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofSeconds(15),
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(20));

    public PlayerServiceLimits {
        if (pendingOperations < 1 || trackedAttempts < 1) {
            throw new IllegalArgumentException("Player service capacities must be positive.");
        }
        for (Duration duration : new Duration[]{loadTimeout, preparedTimeout, mutationTimeout,
                ownershipInterval, ownershipTimeout, cleanupTimeout}) {
            Objects.requireNonNull(duration, "duration");
            if (duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException("Player service deadlines must be positive.");
            }
            duration.toNanos();
        }
    }
}
