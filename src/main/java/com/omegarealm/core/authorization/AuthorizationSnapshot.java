package com.omegarealm.core.authorization;

import java.util.Objects;
import java.util.UUID;

/**
 * Loaded authority at one revision; possession does not establish session liveness or freshness.
 */
public record AuthorizationSnapshot(UUID playerUuid, Role role, long revision) {

    public AuthorizationSnapshot {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(role, "role");
        if (revision < 0) {
            throw new IllegalArgumentException("Invalid authorization revision.");
        }
    }

    public static AuthorizationSnapshot initial(UUID playerUuid) {
        return new AuthorizationSnapshot(playerUuid, Role.PLAYER, 0);
    }

    public AuthorizationActor.Player actor() {
        return new AuthorizationActor.Player(playerUuid, role);
    }
}
