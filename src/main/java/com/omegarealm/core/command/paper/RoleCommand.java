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
import com.omegarealm.core.player.PlayerProfile;
import com.omegarealm.core.player.PlayerService;
import com.omegarealm.core.player.PlayerTarget;
import com.omegarealm.core.player.PlayerTargetResolution;
import com.omegarealm.core.scheduler.OmegaScheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
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
    private final OnlinePlayerTargetSuggestions targetSuggestions;

    RoleCommand(CommandAccess access, Supplier<PlayerService> players, OmegaScheduler scheduler,
                CommandAudit audit, Logger logger, Runnable schedulingFailure,
                BiConsumer<Player, Runnable> playerReply,
                OnlinePlayerTargetSuggestions targetSuggestions) {
        this.access = Objects.requireNonNull(access, "access");
        this.players = Objects.requireNonNull(players, "players");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.schedulingFailure = Objects.requireNonNull(schedulingFailure, "schedulingFailure");
        this.playerReply = Objects.requireNonNull(playerReply, "playerReply");
        this.targetSuggestions = Objects.requireNonNull(targetSuggestions, "targetSuggestions");
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
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((context, builder) -> targetSuggestions.suggest(
                                        CommandCatalog.ROLE, context, builder))
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
        PlayerTarget target;
        try {
            target = PlayerTarget.parse(StringArgumentType.getString(context, "target"));
        } catch (IllegalArgumentException invalid) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    "Use a current username or canonical UUID (xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx).");
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

        String reason;
        try {
            reason = RoleChangeRequest.normalizeReason(StringArgumentType.getString(context, "reason"));
        } catch (IllegalArgumentException invalid) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    "A reason of 1-512 characters without control characters is required.");
            record(actor, Outcome.DENIED, operationId, null);
            return 0;
        }

        // Command access is already established. Explain operation restrictions instead
        // of sending an unknown-command translation with no parser error beneath it.
        if (actor instanceof AuthorizationActor.Player player
                && target instanceof PlayerTarget.ByUuid(UUID uuid1) && player.uuid().equals(uuid1)) {
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
        CompletionStage<PlayerTargetResolution> lookup = service.resolveTarget(target);
        if (!lookup.toCompletableFuture().isCompletedExceptionally()) {
            record(actor, Outcome.QUEUED, operationId, null);
            CommandMessages.send(sender, CommandMessages.Kind.INFO, "Player lookup queued.");
        }
        lookup.whenComplete((resolution, lookupFailure) -> finishLookup(service, actor, target,
                role, reason, operationId, sender, resolution, lookupFailure));
        return 1;
    }

    private void finishLookup(PlayerService service, AuthorizationActor actor, PlayerTarget target,
                              Role role, String reason, UUID operationId,
                              CommandSender sender,
                              PlayerTargetResolution resolution, Throwable failure) {
        if (failure != null) {
            record(actor, Outcome.FAILED, operationId, null);
            reply(sender, CommandMessages.Kind.ERROR,
                    "Player lookup failed; no role change was accepted.");
            return;
        }
        if (resolution.status() == PlayerTargetResolution.Status.NOT_FOUND) {
            record(actor, Outcome.NOT_FOUND, operationId, null);
            String message = target instanceof PlayerTarget.ByUuid
                    ? "No persisted player has that UUID. No role change was made."
                    : "No known player has that current username. No role change was made.";
            reply(sender, CommandMessages.Kind.ERROR, message);
            return;
        }
        if (resolution.status() == PlayerTargetResolution.Status.AMBIGUOUS) {
            record(actor, Outcome.DENIED, operationId, null);
            reply(sender, CommandMessages.Kind.ERROR,
                    "Multiple players have that stored username. Use a UUID; no role change was made.");
            return;
        }

        PlayerProfile profile = resolution.profile();
        if (actor instanceof AuthorizationActor.Player player && player.uuid().equals(profile.uuid())) {
            record(actor, Outcome.DENIED, operationId, null);
            reply(sender, CommandMessages.Kind.ERROR,
                    "You cannot change your own role in-game. Use the local console.");
            return;
        }

        RoleChangeRequest request = new RoleChangeRequest(actor, profile.uuid(), role, reason, operationId);
        CompletionStage<RoleChangeResult> pending = service.changeRole(request);
        pending.whenComplete((result, mutationFailure) -> finishRoleChange(actor, profile,
                operationId, sender, result, mutationFailure));
    }

    private void finishRoleChange(AuthorizationActor actor, PlayerProfile profile, UUID operationId,
                                  CommandSender sender,
                                  RoleChangeResult result, Throwable failure) {
        if (failure != null) {
            record(actor, Outcome.FAILED, operationId, null);
            reply(sender, CommandMessages.Kind.ERROR,
                    "Role change did not complete successfully; inspect audit history before retrying.");
            return;
        }
        record(actor, Outcome.valueOf(result.status().name()), operationId, result.auditId());
        String target = profile.username() + " (" + profile.uuid() + ")";
        String message = switch (result.status()) {
            case CHANGED -> "Role changed: target=" + target
                    + ", role=" + result.authorization().role().name()
                    + ", revision=" + result.authorization().revision() + ", audit=" + result.auditId() + ".";
            case UNCHANGED -> "Role unchanged for " + target + "; no revision or audit record was created.";
            case DENIED -> "Role change denied for " + target
                    + ": you cannot manage this target or role with your current authority. No role change was made.";
            case NOT_FOUND -> "Target " + target + " was not found at role write; no role change was made.";
        };
        CommandMessages.Kind kind = switch (result.status()) {
            case CHANGED, UNCHANGED -> CommandMessages.Kind.SUCCESS;
            case DENIED, NOT_FOUND -> CommandMessages.Kind.ERROR;
        };
        reply(sender, kind, message);
    }

    private void reply(CommandSender sender, CommandMessages.Kind kind, String message) {
        // Replies use the console's global context or this exact connection's entity context.
        try {
            Runnable send = () -> CommandMessages.send(sender, kind, message);
            if (sender instanceof Player player) {
                playerReply.accept(player, send);
            } else {
                scheduler.global(send);
            }
        } catch (RuntimeException unavailable) {
            logger.warning("Role-command reply unavailable; inspect audit history.");
            if (access.ready()) {
                schedulingFailure.run();
            }
        }
    }
}
