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
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(joinedAt, "joinedAt");

        if (username.isBlank() || username.length() > 16 || !username.equals(username.strip())
                || username.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid player username metadata.");
        }

        // PostgreSQL timestamps have microsecond precision; retries compare canonical inputs.
        joinedAt = joinedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
