package com.omegarealm.core.authorization;

import java.util.Objects;
import java.util.Optional;

/**
 * Explicit Omega actions; these keys do not register Bukkit permission defaults.
 */
public enum Capability {
    MANAGE_MODERATOR_ROLE("omega.admin.roles.moderator"),
    MANAGE_ADMIN_ROLE("omega.admin.roles.admin"),
    MANAGE_OWNER_ROLE("omega.admin.roles.owner");

    private final String key;

    Capability(String key) {
        this.key = key;
    }

    /**
     * Unknown keys, wildcards and alternative spellings confer no authority.
     */
    public static Optional<Capability> fromKey(String key) {
        Objects.requireNonNull(key, "key");

        for (Capability capability : values()) {
            if (capability.key.equals(key)) {
                return Optional.of(capability);
            }
        }

        return Optional.empty();
    }

    public String key() {
        return key;
    }
}
