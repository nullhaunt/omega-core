package com.omegarealm.core.player;

import java.util.Objects;
import java.util.UUID;

/**
 * A null nickname means clear; invalid user input receives a controlled result.
 */
public record NicknameChange(UUID playerUuid, long expectedRevision, String nickname) {

    public NicknameChange {
        Objects.requireNonNull(playerUuid, "playerUuid");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("An edit revision is required.");
        }
    }
}
