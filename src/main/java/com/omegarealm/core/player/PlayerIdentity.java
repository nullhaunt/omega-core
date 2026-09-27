package com.omegarealm.core.player;

import java.util.Objects;
import java.util.UUID;

/**
 * Authenticated identity metadata, independent of a Minecraft connection or entity.
 */
public record PlayerIdentity(UUID uuid, String username) {

    public PlayerIdentity {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(username, "username");
        if (username.isBlank() || username.length() > 16 || !username.equals(username.strip())
                || username.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid player username metadata.");
        }
    }
}
