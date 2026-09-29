package com.omegarealm.core.command.paper;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.omegarealm.core.authorization.AuthorizationActor;
import com.omegarealm.core.player.PlayerTarget;
import com.omegarealm.core.player.PlayerTargetResolution;
import com.omegarealm.core.player.PlayerTargetResolver;
import com.omegarealm.core.player.PublicPlayerProfile;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Resolves public profiles on the owned player worker and returns to the requesting connection.
 */
final class ProfileCommand {

    private final CommandAccess access;
    private final Supplier<PlayerTargetResolver> targets;
    private final BiConsumer<Player, Runnable> playerReply;
    private final BiConsumer<Player, PublicPlayerProfile> presenter;
    private final Logger logger;
    private final Runnable schedulingFailure;
    private final OnlinePlayerTargetSuggestions targetSuggestions;

    ProfileCommand(CommandAccess access, Supplier<PlayerTargetResolver> targets,
                   BiConsumer<Player, Runnable> playerReply,
                   BiConsumer<Player, PublicPlayerProfile> presenter,
                   Logger logger, Runnable schedulingFailure,
                   OnlinePlayerTargetSuggestions targetSuggestions) {
        this.access = Objects.requireNonNull(access, "access");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.playerReply = Objects.requireNonNull(playerReply, "playerReply");
        this.presenter = Objects.requireNonNull(presenter, "presenter");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.schedulingFailure = Objects.requireNonNull(schedulingFailure, "schedulingFailure");
        this.targetSuggestions = Objects.requireNonNull(targetSuggestions, "targetSuggestions");
    }

    LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal(CommandCatalog.PROFILE.name())
                .requires(source -> access.allows(CommandCatalog.PROFILE, source))
                .executes(this::self)
                .then(Commands.argument("target", StringArgumentType.greedyString())
                        .suggests((context, builder) -> targetSuggestions.suggest(
                                CommandCatalog.PROFILE, context, builder))
                        .executes(this::target))
                .build();
    }

    private int self(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AuthorizationActor.Player actor = (AuthorizationActor.Player) access.require(CommandCatalog.PROFILE, context);
        return lookup((Player) context.getSource().getSender(), new PlayerTarget.ByUuid(actor.uuid()));
    }

    private int target(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        access.require(CommandCatalog.PROFILE, context);
        Player viewer = (Player) context.getSource().getSender();

        PlayerTarget target;
        try {
            target = PlayerTarget.parse(StringArgumentType.getString(context, "target"));
        } catch (IllegalArgumentException invalid) {
            CommandMessages.send(viewer, CommandMessages.Kind.ERROR,
                    "Enter a current username or full player ID (UUID).");
            return 0;
        }
        return lookup(viewer, target);
    }

    private int lookup(Player viewer, PlayerTarget target) {
        PlayerTargetResolver resolver;
        try {
            resolver = targets.get();
        } catch (IllegalStateException unavailable) {
            CommandMessages.send(viewer, CommandMessages.Kind.ERROR, "Profiles are unavailable right now.");
            return 0;
        }

        CompletionStage<PlayerTargetResolution> pending = resolver.resolveTarget(target);
        CommandMessages.send(viewer, CommandMessages.Kind.INFO, "Loading profile...");
        pending.whenComplete((resolution, failure) -> reply(viewer,
                () -> finish(viewer, resolution, failure)));
        return 1;
    }

    private void finish(Player viewer, PlayerTargetResolution resolution, Throwable failure) {
        if (failure != null) {
            CommandMessages.send(viewer, CommandMessages.Kind.ERROR,
                    "We couldn't load that profile right now. Please try again.");
            return;
        }
        if (resolution.status() == PlayerTargetResolution.Status.NOT_FOUND) {
            CommandMessages.send(viewer, CommandMessages.Kind.ERROR,
                    "We couldn't find a saved profile for that name or player ID.");
            return;
        }
        if (resolution.status() == PlayerTargetResolution.Status.AMBIGUOUS) {
            CommandMessages.send(viewer, CommandMessages.Kind.ERROR,
                    "More than one player has that name. Use a player ID (UUID).");
            return;
        }

        presenter.accept(viewer, resolution.profile());
    }

    private void reply(Player viewer, Runnable action) {
        try {
            // PlayerConnectionAdapter checks the exact connection and current authority.
            playerReply.accept(viewer, action);
        } catch (RuntimeException unavailable) {
            logger.warning("Profile-command reply unavailable; no dialog was opened.");
            if (access.ready()) {
                schedulingFailure.run();
            }
        }
    }
}
