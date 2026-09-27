package com.omegarealm.core.authorization;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * One assigned role includes the authority of all roles below it.
 */
public enum Role {
    PLAYER(null),
    MODERATOR(PLAYER),
    ADMIN(MODERATOR, Capability.MANAGE_MODERATOR_ROLE),
    OWNER(ADMIN, Capability.MANAGE_ADMIN_ROLE);

    private final Role inheritedRole;
    private final Set<Capability> capabilities;

    Role(Role inheritedRole, Capability... grantedCapabilities) {
        this.inheritedRole = inheritedRole;

        Set<Capability> effective = new HashSet<>();
        if (inheritedRole != null) {
            effective.addAll(inheritedRole.capabilities);
        }
        effective.addAll(Set.of(grantedCapabilities));
        capabilities = Set.copyOf(effective);
    }

    public Set<Capability> capabilities() {
        return capabilities;
    }

    public boolean includes(Role role) {
        Objects.requireNonNull(role, "role");

        for (Role current = this; current != null; current = current.inheritedRole) {
            if (current == role) {
                return true;
            }
        }

        return false;
    }

    public boolean isAbove(Role role) {
        Objects.requireNonNull(role, "role");
        return this != role && includes(role);
    }
}
