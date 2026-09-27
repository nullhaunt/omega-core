package com.omegarealm.core.authorization;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable policy input, not proof of authentication or permission freshness.
 * A platform adapter must establish the sender identity and load authoritative roles.
 */
public sealed interface AuthorizationActor {

    record Player(UUID uuid, Role role) implements AuthorizationActor {
        public Player {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(role, "role");
        }
    }

    /**
     * Only the actual local console may be translated to this actor.
     */
    record LocalConsole() implements AuthorizationActor {
    }

    /**
     * Includes RCON, command blocks and other sources without explicit authorization.
     */
    record Untrusted() implements AuthorizationActor {
    }
}
