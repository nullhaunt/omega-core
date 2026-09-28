package com.omegarealm.core.command.paper;

import com.omegarealm.core.authorization.AuthorizationActor;
import com.omegarealm.core.command.CommandAuditEvent;
import com.omegarealm.core.command.CommandAuditEvent.Category;
import com.omegarealm.core.command.CommandAuditEvent.Outcome;
import com.omegarealm.core.player.PlayerService;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Categorized operational logging. The repository writes durable command and mutation events.
 */
public final class CommandAudit {

    private final Logger logger;
    private final String serverId;
    private final Supplier<PlayerService> players;

    public CommandAudit(Logger logger, String serverId, Supplier<PlayerService> players) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.players = Objects.requireNonNull(players, "players");
    }

    /**
     * Shared entry point for significant commands whose effects are outside a database
     * transaction. A successful return means the bounded persistence queue accepted the
     * invocation, not that SQL committed or the native effect succeeded.
     */
    public boolean recordInvocation(CommandAuditEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.outcome() != Outcome.INVOKED) {
            throw new IllegalArgumentException("Expected a command invocation.");
        }
        try {
            if (!players.get().queueCommandAudit(event)) {
                logger.warning("Omega command audit queue unavailable; command was not executed.");
                return false;
            }
        } catch (IllegalStateException unavailable) {
            logger.warning("Omega command audit service unavailable; command was not executed.");
            return false;
        }
        record(event.actor(), event.category(), event.commandPath(), event.outcome(), event.operationId(), null);
        return true;
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
                : actor instanceof AuthorizationActor.Untrusted
                ? "UNTRUSTED" : "LOCAL_CONSOLE, role=LOCAL_CONSOLE";
        String correlation = mutationAuditId == null ? "" : ", mutation_audit=" + mutationAuditId;
        logger.info("Omega command: category=" + category + ", server=" + serverId
                + ", actor=" + identity + ", command=" + command + ", outcome=" + outcome
                + ", operation=" + operationId + correlation + ".");
    }
}
