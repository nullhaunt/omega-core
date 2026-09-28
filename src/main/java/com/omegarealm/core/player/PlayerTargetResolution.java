package com.omegarealm.core.player;

import java.util.Objects;

/**
 * A bounded lookup never guesses between UUIDs sharing the same stored username.
 */
public record PlayerTargetResolution(Status status, PlayerProfile profile) {

    public PlayerTargetResolution {
        Objects.requireNonNull(status, "status");
        if ((status == Status.FOUND) == (profile == null)) {
            throw new IllegalArgumentException("Only a found target has a profile.");
        }
    }

    public static PlayerTargetResolution found(PlayerProfile profile) {
        return new PlayerTargetResolution(Status.FOUND, Objects.requireNonNull(profile, "profile"));
    }

    public static PlayerTargetResolution missing() {
        return new PlayerTargetResolution(Status.NOT_FOUND, null);
    }

    public static PlayerTargetResolution ambiguous() {
        return new PlayerTargetResolution(Status.AMBIGUOUS, null);
    }

    public enum Status {
        FOUND, NOT_FOUND, AMBIGUOUS
    }
}
