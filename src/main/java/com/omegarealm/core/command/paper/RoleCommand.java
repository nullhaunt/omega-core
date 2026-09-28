package com.omegarealm.core.command.paper;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.omegarealm.core.authorization.AuthorizationActor;
import com.omegarealm.core.authorization.Role;
import com.omegarealm.core.authorization.RoleChangeRequest;
import com.omegarealm.core.authorization.RoleChangeResult;
import com.omegarealm.core.command.CommandAuditEvent.Outcome;
import com.omegarealm.core.player.PlayerService;
import com.omegarealm.core.scheduler.OmegaScheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Authenticates exact platform senders; no SQL or waiting on Paper's command thread.
 */
public final class RoleCommand {

    private final CommandAccess access;
    private final Supplier<PlayerService> players;
    private final OmegaScheduler scheduler;
    private final CommandAudit audit;
    private final Logger logger;
    private final Runnable schedulingFailure;
    private final BiConsumer<Player, Runnable> playerReply;

    RoleCommand(CommandAccess access, Supplier<PlayerService> players, OmegaScheduler scheduler,
                CommandAudit audit, Logger logger, Runnable schedulingFailure,
                BiConsumer<Player, Runnable> playerReply) {
        this.access = Objects.requireNonNull(access, "access");
        this.players = Objects.requireNonNull(players, "players");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.schedulingFailure = Objects.requireNonNull(schedulingFailure, "schedulingFailure");
        this.playerReply = Objects.requireNonNull(playerReply, "playerReply");
    }

    private void record(AuthorizationActor actor, Outcome outcome,
                        UUID operationId, UUID mutationAuditId) {
        audit.record(actor, CommandCatalog.ROLE.category(), "role.set", outcome, operationId, mutationAuditId);
    }

    public LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal(CommandCatalog.ROLE.name())
                .requires(source -> access.allows(CommandCatalog.ROLE, source))
                .executes(this::usage)
                .then(Commands.literal("set")
                        .executes(this::usage)
                        .then(Commands.argument("uuid", StringArgumentType.word())
                                .executes(this::usage)
                                .then(Commands.argument("role", StringArgumentType.word())
                                        .executes(this::usage)
                                        .suggests((context, builder) -> {
                                            for (Role role : Role.values()) {
                                                if (access.actor(context.getSource())
                                                        .filter(actor -> CommandCatalog.canAssign(actor, role)).isPresent()
                                                        && role.name().startsWith(builder.getRemaining().toUpperCase(Locale.ROOT))) {
                                                    builder.suggest(role.name());
                                                }
                                            }
                                            return builder.buildFuture();
                                        })
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(this::execute)))))
                .build();
    }

    private int usage(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AuthorizationActor actor = access.require(CommandCatalog.ROLE, context);
        CommandMessages.send(context.getSource().getSender(), CommandMessages.Kind.INFO,
                "Usage: " + CommandCatalog.usage(CommandCatalog.ROLE, actor));
        return 0;
    }

    private int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AuthorizationActor actor = access.require(CommandCatalog.ROLE, context);
        UUID operationId = UUID.randomUUID();
        CommandSourceStack source = context.getSource();
        var sender = source.getSender();
        String identity = StringArgumentType.getString(context, "uuid");
        UUID uuid;
        try {
            uuid = UUID.fromString(identity);
            if (!uuid.toString().equalsIgnoreCase(identity)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    "Use a canonical UUID (xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx).");
            record(actor, Outcome.DENIED, operationId, null);
            return 0;
        }

        Role role;
        try {
            role = Role.valueOf(StringArgumentType.getString(context, "role").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    "Unknown role. Available roles: " + CommandCatalog.availableRoles(actor) + ".");
            record(actor, Outcome.DENIED, operationId, null);
            return 0;
        }

        RoleChangeRequest request;
        try {
            request = new RoleChangeRequest(actor, uuid, role,
                    StringArgumentType.getString(context, "reason"), operationId);
        } catch (IllegalArgumentException invalid) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    "A reason of 1-512 characters without control characters is required.");
            record(actor, Outcome.DENIED, operationId, null);
            return 0;
        }

        // Command access is already established. Explain operation restrictions instead
        // of sending an unknown-command translation with no parser error beneath it.
        if (actor instanceof AuthorizationActor.Player player && player.uuid().equals(uuid)) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    "You cannot change your own role in-game. Use the local console.");
            record(actor, Outcome.DENIED, operationId, null);
            return 0;
        }
        if (!CommandCatalog.canAssign(actor, role)) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    "Your role can assign " + CommandCatalog.availableRoles(actor)
                            + ". Only the local console can assign OWNER.");
            record(actor, Outcome.DENIED, operationId, null);
            return 0;
        }
        if (!access.ready()) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    "Omega Core is unavailable; no role change was accepted.");
            record(actor, Outcome.FAILED, operationId, null);
            return 0;
        }
        PlayerService service;
        try {
            service = players.get();
        } catch (IllegalStateException unavailable) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    "Omega Core is unavailable; no role change was accepted.");
            record(actor, Outcome.FAILED, operationId, null);
            return 0;
        }
        CompletionStage<RoleChangeResult> pending = service.changeRole(request);
        if (!pending.toCompletableFuture().isCompletedExceptionally()) {
            record(actor, Outcome.QUEUED, operationId, null);
            CommandMessages.send(sender, CommandMessages.Kind.INFO, "Role change queued.");
        }
        pending.whenComplete((result, failure) -> {
            // Replies use the console's global context or this exact connection's entity context.
            // Once accepted, persistence may finish after disconnect; its audit remains durable.
            try {
                if (failure != null) {
                    record(actor, Outcome.FAILED, operationId, null);
                } else {
                    record(actor, Outcome.valueOf(result.status().name()),
                            operationId, result.auditId());
                }
                Runnable reply = () -> {
                    if (failure != null) {
                        CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                                "Role change did not complete successfully; inspect audit history before retrying.");
                        return;
                    }
                    String message = switch (result.status()) {
                        case CHANGED -> "Role changed: target=" + result.authorization().playerUuid()
                                + ", role=" + result.authorization().role().name()
                                + ", revision=" + result.authorization().revision() + ", audit=" + result.auditId() + ".";
                        case UNCHANGED -> "Role unchanged; no revision or audit record was created.";
                        case DENIED ->
                                "Role change denied: you cannot manage this target or role with your current authority. No role change was made.";
                        case NOT_FOUND -> "Target was not found at role lookup; no role change was made.";
                    };
                    CommandMessages.Kind kind = switch (result.status()) {
                        case CHANGED, UNCHANGED -> CommandMessages.Kind.SUCCESS;
                        case DENIED, NOT_FOUND -> CommandMessages.Kind.ERROR;
                    };
                    CommandMessages.send(sender, kind, message);
                };
                if (sender instanceof Player player) {
                    playerReply.accept(player, reply);
                } else {
                    scheduler.global(reply);
                }
            } catch (RuntimeException unavailable) {
                logger.warning("Role-change reply unavailable; inspect durable audit history.");
                if (access.ready()) {
                    schedulingFailure.run();
                }
            }
        });
        return 1;
    }
}
