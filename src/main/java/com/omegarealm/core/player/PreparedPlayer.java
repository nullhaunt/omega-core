package com.omegarealm.core.player;

import com.omegarealm.core.authorization.AuthorizationSnapshot;

import java.util.Objects;
import java.util.Optional;

/**
 * A new player has no persisted profile until an actual successful world join.
 */
public record PreparedPlayer(PlayerAttempt attempt, Optional<PlayerProfile> storedProfile,
                             AuthorizationSnapshot authorization) {

    public PreparedPlayer {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(storedProfile, "storedProfile");
        Objects.requireNonNull(authorization, "authorization");
        // Share loaded-data invariants even when constructed outside the repository.
        new LoadedPlayer(storedProfile, authorization);
        if (!attempt.identity().uuid().equals(authorization.playerUuid())) {
            throw new IllegalArgumentException("Prepared player identity mismatch.");
        }
    }
}
