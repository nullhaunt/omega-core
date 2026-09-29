package com.omegarealm.core.command.paper;

import com.mojang.brigadier.arguments.ArgumentType;
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

import java.util.List;
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

    public LiteralCommandNode<CommandSourceStack> node(ArgumentType<?> targetType) {
        return Commands.literal(CommandCatalog.ROLE.name())
                .requires(source -> access.allows(CommandCatalog.ROLE, source))
                .executes(this::usage)
                .then(Commands.literal("set")
                        .executes(this::usage)
                        .then(Commands.argument("target", targetType)
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
        CommandMessages.sendPanel(context.getSource().getSender(), "Set a player's role", List.of(
                CommandMessages.Field.normal("Command", CommandCatalog.usage(CommandCatalog.ROLE, actor)),
                CommandMessages.Field.normal("Roles you can assign", CommandCatalog.assignableRoleNames(actor))));
        return 0;
    }

    private int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AuthorizationActor actor = access.require(CommandCatalog.ROLE, context);
        UUID operationId = UUID.randomUUID();
        CommandSourceStack source = context.getSource();
        var sender = source.getSender();
        PlayerTarget target;
        try {
            target = PlayerTargetArgument.parse(context);
        } catch (IllegalArgumentException invalid) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    "Enter a current username or full player ID (UUID).");
            record(actor, Outcome.DENIED, operationId, null);
            return 0;
        }

        Role role;
        try {
            role = Role.valueOf(StringArgumentType.getString(context, "role").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    "Choose a role you can assign: " + CommandCatalog.assignableRoleNames(actor) + ".");
            record(actor, Outcome.DENIED, operationId, null);
            return 0;
        }

        String reason;
        try {
            reason = RoleChangeRequest.normalizeReason(StringArgumentType.getString(context, "reason"));
        } catch (IllegalArgumentException invalid) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    "Enter a one-line reason between 1 and 512 characters.");
            record(actor, Outcome.DENIED, operationId, null);
            return 0;
        }

        // Command access is already established. Explain operation restrictions instead
        // of sending an unknown-command translation with no parser error beneath it.
        if (actor instanceof AuthorizationActor.Player player
                && target instanceof PlayerTarget.ByUuid(UUID uuid1) && player.uuid().equals(uuid1)) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    "Use the server console to change your own role.");
            record(actor, Outcome.DENIED, operationId, null);
            return 0;
        }
        if (!CommandCatalog.canAssign(actor, role)) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    "You can assign " + CommandCatalog.assignableRoleNames(actor)
                            + ". Only the server console can assign Owner.");
            record(actor, Outcome.DENIED, operationId, null);
            return 0;
        }
        if (!access.ready()) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    "Role changes are unavailable right now. Nothing was changed.");
            record(actor, Outcome.FAILED, operationId, null);
            return 0;
        }
        PlayerService service;
        try {
            service = players.get();
        } catch (IllegalStateException unavailable) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    "Role changes are unavailable right now. Nothing was changed.");
            record(actor, Outcome.FAILED, operationId, null);
            return 0;
        }
        CompletionStage<PlayerTargetResolution> lookup = service.resolveTarget(target);
        if (!lookup.toCompletableFuture().isCompletedExceptionally()) {
            record(actor, Outcome.QUEUED, operationId, null);
            CommandMessages.send(sender, CommandMessages.Kind.INFO, "Looking up the player...");
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
            replyNotice(sender, CommandMessages.Kind.ERROR, "Role not changed",
                    "We couldn't check that player right now. Please try again.");
            return;
        }
        if (resolution.status() == PlayerTargetResolution.Status.NOT_FOUND) {
            record(actor, Outcome.NOT_FOUND, operationId, null);
            String message = target instanceof PlayerTarget.ByUuid
                    ? "No saved player has that ID."
                    : "No saved player has that current name.";
            replyNotice(sender, CommandMessages.Kind.ERROR, "Role not changed", message);
            return;
        }
        if (resolution.status() == PlayerTargetResolution.Status.AMBIGUOUS) {
            record(actor, Outcome.DENIED, operationId, null);
            replyNotice(sender, CommandMessages.Kind.ERROR, "Role not changed",
                    "More than one player has that name. Use a player ID (UUID) to choose one.");
            return;
        }

        PlayerProfile profile = resolution.profile().identity();
        if (actor instanceof AuthorizationActor.Player player && player.uuid().equals(profile.uuid())) {
            record(actor, Outcome.DENIED, operationId, null);
            replyNotice(sender, CommandMessages.Kind.ERROR, "Role not changed",
                    "Use the server console to change your own role.");
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
            replyNotice(sender, CommandMessages.Kind.ERROR, "Couldn't confirm the role change",
                    "Check the audit history before trying again.");
            return;
        }
        record(actor, Outcome.valueOf(result.status().name()), operationId, result.auditId());
        String title = switch (result.status()) {
            case CHANGED -> "Role updated";
            case UNCHANGED -> "No change needed";
            case DENIED, NOT_FOUND -> "Role not changed";
        };
        String detail = switch (result.status()) {
            case CHANGED -> profile.username() + " now has the "
                    + CommandCatalog.roleName(result.authorization().role()) + " role.";
            case UNCHANGED -> profile.username() + " already has the "
                    + CommandCatalog.roleName(result.authorization().role()) + " role.";
            case DENIED -> "You can't manage " + profile.username() + " or assign that role.";
            case NOT_FOUND -> "We couldn't find " + profile.username() + " when saving the change.";
        };
        CommandMessages.Kind kind = switch (result.status()) {
            case CHANGED -> CommandMessages.Kind.SUCCESS;
            case UNCHANGED -> CommandMessages.Kind.INFO;
            case DENIED, NOT_FOUND -> CommandMessages.Kind.ERROR;
        };
        replyNotice(sender, kind, title, detail);
    }

    private void replyNotice(CommandSender sender, CommandMessages.Kind kind,
                             String title, String detail) {
        // Replies use the console's global context or this exact connection's entity context.
        try {
            Runnable send = () -> CommandMessages.sendNotice(sender, kind, title, detail);
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
