package com.omegarealm.core.player;

import java.time.Duration;
import java.util.Optional;

/**
 * Bounded, in-memory observations of the owned player service. Ages use a monotonic clock.
 */
public record PlayerServiceDiagnostics(boolean running, int connectedAttempts,
                                       int trackedOpenSessions, int pendingOperations,
                                       Optional<Duration> ownershipVerifiedAgo,
                                       boolean ownershipCheckPending,
                                       Optional<Duration> oldestAuthorizationAge,
                                       int expiredAuthorizations) {
}
