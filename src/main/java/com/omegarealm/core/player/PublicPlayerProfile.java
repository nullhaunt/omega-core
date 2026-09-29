package com.omegarealm.core.player;

import com.omegarealm.core.authorization.Role;

import java.util.Objects;
import java.util.Optional;

/**
 * Public identity composed with the current stored role at lookup time.
 */
public record PublicPlayerProfile(PlayerProfile identity, Role role) {

    public PublicPlayerProfile {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(role, "role");
    }

    public Optional<String> staffDesignation() {
        return switch (role) {
            case PLAYER -> Optional.empty();
            case MODERATOR -> Optional.of("Moderator");
            case ADMIN -> Optional.of("Admin");
            case OWNER -> Optional.of("Owner");
        };
    }
}
