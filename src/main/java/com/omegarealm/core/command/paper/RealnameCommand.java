package com.omegarealm.core.command.paper;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.omegarealm.core.authorization.AuthorizationActor;
import com.omegarealm.core.player.PlayerTarget;
import com.omegarealm.core.player.PlayerTargetResolution;
import com.omegarealm.core.player.PlayerTargetResolver;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Optional identity lookup using exactly the same resolver as moderation and profiles.
 */
final class RealnameCommand {

    private final CommandAccess access;
    private final Supplier<PlayerTargetResolver> targets;
    private final CommandReplies replies;
    private final PlayerTargetSuggestions suggestions;

    RealnameCommand(CommandAccess access, Supplier<PlayerTargetResolver> targets,
                    CommandReplies replies, PlayerTargetSuggestions suggestions) {
        this.access = Objects.requireNonNull(access, "access");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.replies = Objects.requireNonNull(replies, "replies");
        this.suggestions = Objects.requireNonNull(suggestions, "suggestions");
    }

    LiteralCommandNode<CommandSourceStack> node(ArgumentType<?> targetType) {
        return Commands.literal(CommandCatalog.REALNAME.name())
                .requires(source -> access.allows(CommandCatalog.REALNAME, source))
                .executes(this::usage)
                .then(Commands.argument("target", targetType)
                        .suggests((context, builder) -> suggestions.suggest(CommandCatalog.REALNAME, context, builder))
                        .executes(this::execute))
                .build();
    }

    private int usage(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AuthorizationActor actor = access.require(CommandCatalog.REALNAME, context);
        CommandMessages.sendPanel(context.getSource().getSender(), CommandCatalog.REALNAME.description(), List.of(
                CommandMessages.Field.normal("Command", CommandCatalog.REALNAME.usage(actor))));
        return 0;
    }

    private int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        access.require(CommandCatalog.REALNAME, context);
        CommandSender sender = context.getSource().getSender();
        PlayerTarget target;
        try {
            target = PlayerTargetArgument.parse(context);
        } catch (IllegalArgumentException invalid) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR,
                    "Enter a current username, nickname, or full player ID (UUID).");
            return 0;
        }
        if (!access.ready()) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR, "Player lookup is unavailable right now.");
            return 0;
        }
        PlayerTargetResolver resolver;
        try {
            resolver = targets.get();
        } catch (IllegalStateException unavailable) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR, "Player lookup is unavailable right now.");
            return 0;
        }
        resolver.resolveTarget(target).whenComplete((result, failure) -> replies.dispatch(sender,
                () -> finish(sender, result, failure)));
        return 1;
    }

    private void finish(CommandSender sender, PlayerTargetResolution result, Throwable failure) {
        if (failure != null) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR, "We couldn't check that player right now. Please try again.");
        } else if (result.status() == PlayerTargetResolution.Status.NOT_FOUND) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR, "No saved player has that current name or player ID.");
        } else if (result.status() == PlayerTargetResolution.Status.AMBIGUOUS) {
            CommandMessages.send(sender, CommandMessages.Kind.ERROR, "More than one player has that name. Use a player ID (UUID).");
        } else {
            List<CommandMessages.Field> fields = new ArrayList<>();
            fields.add(CommandMessages.Field.normal("Account", result.profile().identity().username()));
            result.profile().nickname().ifPresent(nickname -> fields.add(CommandMessages.Field.normal("Nickname", nickname)));
            CommandMessages.sendPanel(sender, "Player identity", fields);
        }
    }
}
