package com.omegarealm.core.player;

import java.util.Objects;
import java.util.UUID;

/**
 * A command-facing UUID or name token. A name may be a current username or nickname;
 * it becomes an authoritative identity only after persisted resolution.
 */
public sealed interface PlayerTarget permits PlayerTarget.ByUuid, PlayerTarget.ByName {

    static PlayerTarget parse(String input) {
        Objects.requireNonNull(input, "input");
        if (input.length() == 36) {
            try {
                UUID uuid = UUID.fromString(input);
                if (uuid.toString().equalsIgnoreCase(input)) {
                    return new ByUuid(uuid);
                }
            } catch (IllegalArgumentException invalidUuid) {
                // An invalid UUID-shaped token is rejected by the name rule below.
            }
        }
        return new ByName(input);
    }

    record ByUuid(UUID uuid) implements PlayerTarget {
        public ByUuid {
            Objects.requireNonNull(uuid, "uuid");
        }
    }

    record ByName(String name) implements PlayerTarget {
        public ByName {
            Objects.requireNonNull(name, "name");
            // Floodgate's default prefix is part of the stored username, including for
            // players whose Bedrock gamertag matches a separate Java account.
            // Nicknames share the unprefixed alphabet. Short account names remain valid
            // targets even though a nickname requires at least three characters.
            if (!name.matches("(?:[A-Za-z0-9_]{1,16}|\\.[A-Za-z0-9_]{1,15})")) {
                throw new IllegalArgumentException("Use a canonical UUID, current username, or nickname.");
            }
        }
    }
}
