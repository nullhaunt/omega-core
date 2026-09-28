package com.omegarealm.core.authorization;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable intent. A supplied player role is never trusted by the persistence writer.
 * The operation ID links operational messages to accepted and final command events.
 */
public record RoleChangeRequest(AuthorizationActor actor, UUID targetUuid, Role role,
                                String reason, UUID operationId) {

    public RoleChangeRequest(AuthorizationActor actor, UUID targetUuid, Role role, String reason) {
        this(actor, targetUuid, role, reason, UUID.randomUUID());
    }

    public RoleChangeRequest {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(targetUuid, "targetUuid");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(operationId, "operationId");
        reason = normalizeReason(reason);
    }

    public static String normalizeReason(String reason) {
        Objects.requireNonNull(reason, "reason");
        reason = reason.strip();
        if (reason.isEmpty() || reason.length() > 512 || reason.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("A role change requires a reason of 1-512 characters without control characters.");
        }
        return reason;
    }
}
