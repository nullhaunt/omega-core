package com.omegarealm.core.player;

import java.util.Objects;
import java.util.Optional;

/**
 * A new player has no persisted profile until an actual successful world join.
 */
public record PreparedPlayer(PlayerAttempt attempt, Optional<PlayerProfile> storedProfile) {

    public PreparedPlayer {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(storedProfile, "storedProfile");
    }
}
