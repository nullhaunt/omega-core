package com.omegarealm.core.authorization;

import java.util.Objects;

/**
 * Pure policy evaluation; performs no persistence, platform access or role mutation.
 */
public final class AuthorizationPolicy {

    private AuthorizationPolicy() {
    }

    public static boolean allows(AuthorizationActor actor, Capability capability) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(capability, "capability");

        if (actor instanceof AuthorizationActor.LocalConsole) {
            return true;
        }
        if (actor instanceof AuthorizationActor.Player player) {
            return player.role().capabilities().contains(capability);
        }

        return false;
    }

    public static boolean allows(AuthorizationActor actor, String capabilityKey) {
        Objects.requireNonNull(actor, "actor");
        return Capability.fromKey(capabilityKey)
                .map(capability -> allows(actor, capability))
                .orElse(false);
    }

    /**
     * Authorizes replacement of one role, including promotion, demotion and revocation
     * to PLAYER. A future mutation service must recheck authoritative actor and target
     * roles atomically with the write; a previously authorized snapshot is insufficient.
     */
    public static boolean canChangeRole(AuthorizationActor actor,
                                        AuthorizationActor.Player target, Role requestedRole) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(requestedRole, "requestedRole");

        if (actor instanceof AuthorizationActor.LocalConsole) {
            return true;
        }
        if (!(actor instanceof AuthorizationActor.Player player)) {
            return false;
        }
        if (!allows(player, Capability.MANAGE_MODERATOR_ROLE)) {
            return false;
        }

        // Checking the requested role alone would allow demoting a protected peer.
        if (player.uuid().equals(target.uuid()) || !player.role().isAbove(target.role())) {
            return false;
        }

        return canManageRole(player, target.role()) && canManageRole(player, requestedRole);
    }

    private static boolean canManageRole(AuthorizationActor actor, Role role) {
        return switch (role) {
            case PLAYER -> true;
            case MODERATOR -> allows(actor, Capability.MANAGE_MODERATOR_ROLE);
            case ADMIN -> allows(actor, Capability.MANAGE_ADMIN_ROLE);
            case OWNER -> allows(actor, Capability.MANAGE_OWNER_ROLE);
        };
    }
}
