package com.omegarealm.core.authorization;

import java.util.Objects;
import java.util.UUID;

/**
 * A CHANGED result is returned only after both authority and audit history commit.
 */
public record RoleChangeResult(Status status, AuthorizationSnapshot authorization, UUID auditId) {

    public RoleChangeResult {
        Objects.requireNonNull(status, "status");
        if ((status == Status.CHANGED || status == Status.UNCHANGED) == (authorization == null)
                || (status == Status.CHANGED) == (auditId == null)) {
            throw new IllegalArgumentException("Inconsistent role change result.");
        }
    }

    public enum Status {
        CHANGED, UNCHANGED, DENIED, NOT_FOUND
    }
}
