package com.omegarealm.core.player;

import com.omegarealm.core.authorization.AuthorizationSnapshot;
import com.omegarealm.core.authorization.Role;

import java.util.Objects;
import java.util.Optional;

/**
 * Profile and authorization from the same database view; loading has no write side effects.
 */
public record LoadedPlayer(Optional<PlayerProfile> storedProfile, AuthorizationSnapshot authorization) {

    public LoadedPlayer {
        Objects.requireNonNull(storedProfile, "storedProfile");
        Objects.requireNonNull(authorization, "authorization");

        if (storedProfile.isPresent()) {
            if (!storedProfile.get().uuid().equals(authorization.playerUuid())) {
                throw new IllegalArgumentException("Loaded player identity mismatch.");
            }
        } else if (authorization.role() != Role.PLAYER || authorization.revision() != 0) {
            throw new IllegalArgumentException("Unpersisted player requires initial authorization.");
        }
    }
}
