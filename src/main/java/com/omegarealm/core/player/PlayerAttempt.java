package com.omegarealm.core.player;

import java.util.Objects;
import java.util.UUID;

/**
 * Unique token for one connection attempt. Reconnect must allocate a new token.
 */
public record PlayerAttempt(UUID token, PlayerIdentity identity) {

    public PlayerAttempt {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(identity, "identity");
    }
}
