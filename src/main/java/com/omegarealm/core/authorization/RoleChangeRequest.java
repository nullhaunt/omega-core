package com.omegarealm.core.authorization;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable intent. A supplied player role is never trusted by the persistence writer.
 */
public record RoleChangeRequest(AuthorizationActor actor, UUID targetUuid, Role role, String reason) {

    public RoleChangeRequest {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(targetUuid, "targetUuid");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(reason, "reason");
        reason = reason.strip();
        if (reason.isEmpty() || reason.length() > 512 || reason.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("A role change requires a reason of 1-512 characters without control characters.");
        }
    }
}
