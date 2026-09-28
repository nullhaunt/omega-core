package com.omegarealm.core.command;

import com.omegarealm.core.authorization.AuthorizationActor;
import com.omegarealm.core.authorization.Role;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, argument-free command event shared by operational and durable audits.
 */
public record CommandAuditEvent(UUID operationId, AuthorizationActor actor, Category category,
                                String commandPath, Outcome outcome, UUID targetUuid, Role requestedRole,
                                UUID roleChangeAuditId) {

    public CommandAuditEvent {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(commandPath, "commandPath");
        Objects.requireNonNull(outcome, "outcome");
        if (!commandPath.matches("[a-z][a-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Invalid canonical command path.");
        }
        if (commandPath.equals("role.set") && (targetUuid == null || requestedRole == null)) {
            throw new IllegalArgumentException("A role command audit requires a target and requested role.");
        }
    }

    public enum Category {
        STAFF, MANAGEMENT
    }

    public enum Outcome {
        INVOKED, QUEUED, CHANGED, UNCHANGED, DENIED, NOT_FOUND, FAILED
    }
}
