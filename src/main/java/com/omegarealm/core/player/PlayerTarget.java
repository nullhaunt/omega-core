package com.omegarealm.core.player;

import java.util.Objects;
import java.util.UUID;

/**
 * A command-facing reference. Resolution always returns a persisted UUID and current name.
 */
public sealed interface PlayerTarget permits PlayerTarget.ByUuid, PlayerTarget.ByUsername {

    static PlayerTarget parse(String input) {
        Objects.requireNonNull(input, "input");
        if (input.length() == 36) {
            try {
                UUID uuid = UUID.fromString(input);
                if (uuid.toString().equalsIgnoreCase(input)) {
                    return new ByUuid(uuid);
                }
            } catch (IllegalArgumentException invalidUuid) {
                // An invalid UUID-shaped token is rejected by the username rule below.
            }
        }
        return new ByUsername(input);
    }

    record ByUuid(UUID uuid) implements PlayerTarget {
        public ByUuid {
            Objects.requireNonNull(uuid, "uuid");
        }
    }

    record ByUsername(String username) implements PlayerTarget {
        public ByUsername {
            Objects.requireNonNull(username, "username");
            // Floodgate's default prefix is part of the stored username, including for
            // players whose Bedrock gamertag matches a separate Java account.
            if (!username.matches("(?:[A-Za-z0-9_]{1,16}|\\.[A-Za-z0-9_]{1,15})")) {
                throw new IllegalArgumentException("Use a canonical UUID or a current stored username.");
            }
        }
    }
}
