package com.omegarealm.core.command.paper;

import com.omegarealm.core.authorization.AuthorizationActor;

import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Categorized operational events. Domain mutation audits remain transactionally durable.
 */
public final class CommandAudit {

    private final Logger logger;
    private final String serverId;

    public CommandAudit(Logger logger, String serverId) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
    }

    public void record(AuthorizationActor actor, Category category, String command,
                       Outcome outcome, UUID operationId, UUID mutationAuditId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(operationId, "operationId");

        String identity = actor instanceof AuthorizationActor.Player(
                UUID uuid, com.omegarealm.core.authorization.Role role
        )
                ? "PLAYER:" + uuid + ", role=" + role.name()
                : "LOCAL_CONSOLE, role=LOCAL_CONSOLE";
        String correlation = mutationAuditId == null ? "" : ", mutation_audit=" + mutationAuditId;
        logger.info("Omega command: category=" + category + ", server=" + serverId
                + ", actor=" + identity + ", command=" + command + ", outcome=" + outcome
                + ", operation=" + operationId + correlation + ".");
    }

    public enum Category {
        STAFF, MANAGEMENT
    }

    public enum Outcome {
        INVOKED, QUEUED, CHANGED, UNCHANGED, DENIED, NOT_FOUND, FAILED
    }
}
