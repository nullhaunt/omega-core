package com.omegarealm.core.player;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable successful-join input. The session ID is the connection's idempotency token.
 */
public record SessionStart(UUID sessionId, UUID playerUuid, String username, Instant joinedAt) {

    public SessionStart {
        Objects.requireNonNull(sessionId, "sessionId");
        new PlayerIdentity(playerUuid, username);
        Objects.requireNonNull(joinedAt, "joinedAt");

        // PostgreSQL timestamps have microsecond precision; retries compare canonical inputs.
        joinedAt = joinedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
