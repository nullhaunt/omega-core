package com.omegarealm.core.command.paper;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.omegarealm.core.authorization.AuthorizationActor;
import com.omegarealm.core.command.CommandAuditEvent;
import com.omegarealm.core.command.CommandAuditEvent.Outcome;
import com.omegarealm.core.player.PlayerTarget;
import com.omegarealm.core.player.paper.BedrockSkinAdapter;
import com.omegarealm.core.scheduler.OmegaScheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Refreshes one connected Floodgate player's Java-visible skin without tick-thread HTTP.
 */
final class SkinCommand {

    private final CommandAccess access;
    private final Supplier<BedrockSkinAdapter> skins;
    private final OmegaScheduler scheduler;
    private final CommandAudit audit;
    private final Logger logger;
    private final Runnable schedulingFailure;
    private final BiConsumer<Player, Runnable> playerReply;
    private final OnlinePlayerTargetSuggestions targetSuggestions;

    SkinCommand(CommandAccess access, Supplier<BedrockSkinAdapter> skins,
                OmegaScheduler scheduler, CommandAudit audit, Logger logger,
                Runnable schedulingFailure, BiConsumer<Player, Runnable> playerReply,
                OnlinePlayerTargetSuggestions targetSuggestions) {
        this.access = Objects.requireNonNull(access, "access");
        this.skins = Objects.requireNonNull(skins, "skins");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.schedulingFailure = Objects.requireNonNull(schedulingFailure, "schedulingFailure");
        this.playerReply = Objects.requireNonNull(playerReply, "playerReply");
        this.targetSuggestions = Objects.requireNonNull(targetSuggestions, "targetSuggestions");
    }

    private static boolean matches(PlayerTarget target, Player player) {
        return target instanceof PlayerTarget.ByUuid(UUID uuid1)
                ? player.getUniqueId().equals(uuid1)
                : player.getName().equalsIgnoreCase(((PlayerTarget.ByUsername) target).username());
    }

    private static void finish(CommandSender sender, String name,
                               BedrockSkinAdapter.RefreshResult result, Throwable failure) {
        if (failure != null) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    "Skin refresh failed. Please try again.");
            return;
        }

        switch (result) {
            case APPLIED -> CommandMessages.send(sender, CommandMessages.Kind.SUCCESS,
                    "Refreshed " + name + "'s skin for Java players.");
            case NOT_BEDROCK -> CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    name + " is not connected through Bedrock.");
            case NO_CONVERTED_SKIN -> CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    "No converted Bedrock skin is available for " + name + " yet.");
            case BUSY -> CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    "Skin refresh is busy. Please try again shortly.");
            case ALREADY_CURRENT -> CommandMessages.send(sender, CommandMessages.Kind.INFO,
                    name + " already has a converted skin.");
            case UNAVAILABLE, FAILED, CANCELLED -> CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    "Skin refresh could not finish. Please try again.");
        }
    }

    LiteralCommandNode<CommandSourceStack> node(ArgumentType<?> targetType) {
        return Commands.literal(CommandCatalog.SKIN.name())
                .requires(source -> access.allows(CommandCatalog.SKIN, source))
                .executes(this::usage)
                .then(Commands.literal("refresh")
                        .executes(this::usage)
                        .then(Commands.argument("target", targetType)
                                .suggests((context, builder) -> targetSuggestions.suggest(
                                        CommandCatalog.SKIN, context, builder))
                                .executes(this::refresh)))
                .build();
    }

    private int usage(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AuthorizationActor actor = access.require(CommandCatalog.SKIN, context);
        CommandMessages.sendPanel(context.getSource().getSender(), CommandCatalog.SKIN.description(), List.of(
                CommandMessages.Field.normal("Command", CommandCatalog.SKIN.usage(actor))));
        return 0;
    }

    private int refresh(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AuthorizationActor actor = access.require(CommandCatalog.SKIN, context);
        CommandSender sender = context.getSource().getSender();
        UUID operationId = UUID.randomUUID();
        PlayerTarget target;
        try {
            target = PlayerTargetArgument.parse(context);
        } catch (IllegalArgumentException invalid) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    "Enter a connected player's current name or full player ID (UUID).");
            record(actor, Outcome.DENIED, operationId);
            return 0;
        }

        List<? extends Player> matches = Bukkit.getOnlinePlayers().stream()
                .filter(player -> matches(target, player))
                .toList();
        if (matches.size() != 1) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR, matches.isEmpty()
                    ? "That player is not connected."
                    : "More than one player has that name. Use a player ID (UUID).");
            record(actor, matches.isEmpty() ? Outcome.NOT_FOUND : Outcome.DENIED, operationId);
            return 0;
        }

        BedrockSkinAdapter adapter = skins.get();
        if (adapter == null || !access.ready()) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    "Bedrock skin refresh is unavailable right now.");
            record(actor, Outcome.FAILED, operationId);
            return 0;
        }

        Player player = matches.getFirst();
        String playerName = player.getName();
        CommandAuditEvent invoked = new CommandAuditEvent(operationId, actor,
                CommandCatalog.SKIN.category(), "skin.refresh", Outcome.INVOKED,
                player.getUniqueId(), null, null);
        if (!audit.recordInvocation(invoked)) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    "Skin refresh is unavailable while command auditing is busy.");
            return 0;
        }

        CommandMessages.send(sender, CommandMessages.Kind.INFO,
                "Refreshing " + playerName + "'s skin...");
        adapter.refresh(player).whenComplete((result, failure) -> {
            Outcome outcome = failure != null ? Outcome.FAILED : switch (result) {
                case APPLIED -> Outcome.CHANGED;
                case ALREADY_CURRENT -> Outcome.UNCHANGED;
                case NOT_BEDROCK -> Outcome.DENIED;
                case NO_CONVERTED_SKIN -> Outcome.NOT_FOUND;
                case BUSY, UNAVAILABLE, FAILED, CANCELLED -> Outcome.FAILED;
            };
            record(actor, outcome, operationId);
            reply(sender, () -> finish(sender, playerName, result, failure));
        });
        return 1;
    }

    private void record(AuthorizationActor actor, Outcome outcome, UUID operationId) {
        audit.record(actor, CommandCatalog.SKIN.category(), "skin.refresh", outcome, operationId, null);
    }

    private void reply(CommandSender sender, Runnable send) {
        try {
            if (sender instanceof Player player) {
                playerReply.accept(player, send);
            } else {
                scheduler.global(send);
            }
        } catch (RuntimeException unavailable) {
            logger.warning("Skin-command reply unavailable; inspect the command audit.");
            if (access.ready()) {
                schedulingFailure.run();
            }
        }
    }
}
