package com.omegarealm.core.command.paper;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.omegarealm.core.player.NicknameChangeResult;
import com.omegarealm.core.player.NicknamePolicy;
import com.omegarealm.core.player.PlayerNickname;
import com.omegarealm.core.player.PlayerService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Self-service edits derive identity from a live token and preserve optimistic revisions.
 */
final class NicknameCommand {

    private final CommandAccess access;
    private final Supplier<PlayerService> players;
    private final Function<Player, Optional<UUID>> connectionToken;
    private final CommandReplies replies;

    NicknameCommand(CommandAccess access, Supplier<PlayerService> players,
                    Function<Player, Optional<UUID>> connectionToken, CommandReplies replies) {
        this.access = Objects.requireNonNull(access, "access");
        this.players = Objects.requireNonNull(players, "players");
        this.connectionToken = Objects.requireNonNull(connectionToken, "connectionToken");
        this.replies = Objects.requireNonNull(replies, "replies");
    }

    LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal(CommandCatalog.NICKNAME.name())
                .requires(source -> access.allows(CommandCatalog.NICKNAME, source))
                .executes(context -> execute(context, false, null))
                .then(Commands.argument("nickname", StringArgumentType.greedyString())
                        .suggests((context, builder) -> {
                            if (access.allows(CommandCatalog.NICKNAME, context.getSource())
                                    && "clear".startsWith(NicknamePolicy.key(builder.getRemaining()))) {
                                builder.suggest("clear");
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            String input = StringArgumentType.getString(context, "nickname");
                            String nickname = NicknamePolicy.key(input).equals("clear") ? null : input;
                            return execute(context, true, nickname);
                        }))
                .build();
    }

    private int execute(CommandContext<CommandSourceStack> context, boolean edit,
                        String nickname) throws CommandSyntaxException {
        access.require(CommandCatalog.NICKNAME, context);
        Player viewer = (Player) context.getSource().getSender();
        if (edit && nickname != null && !NicknamePolicy.valid(nickname)) {
            CommandMessages.send(viewer, CommandMessages.Kind.ERROR,
                    "Use 3–16 letters, digits, or underscores for your nickname.");
            return 0;
        }
        if (edit && nickname != null && NicknamePolicy.reserved(nickname)) {
            CommandMessages.send(viewer, CommandMessages.Kind.ERROR, "That name is reserved for a staff label.");
            return 0;
        }
        Optional<UUID> token = connectionToken.apply(viewer);
        if (token.isEmpty()) {
            CommandMessages.send(viewer, CommandMessages.Kind.ERROR, "Nicknames are unavailable on this connection.");
            return 0;
        }
        PlayerService service;
        try {
            service = players.get();
        } catch (IllegalStateException unavailable) {
            CommandMessages.send(viewer, CommandMessages.Kind.ERROR, "Nicknames are unavailable right now.");
            return 0;
        }

        service.loadNickname(token.get()).whenComplete((current, failure) -> {
            if (failure != null || current.isEmpty()) {
                replies.dispatch(viewer, () -> CommandMessages.send(viewer, CommandMessages.Kind.ERROR,
                        "We couldn't read your nickname right now. Please try again."));
            } else if (!edit) {
                replies.dispatch(viewer, () -> show(viewer, current.orElseThrow()));
            } else {
                service.changeNickname(token.get(), current.orElseThrow().revision(), nickname)
                        .whenComplete((result, writeFailure) -> replies.dispatch(viewer,
                                () -> finish(viewer, result, writeFailure)));
            }
        });
        return 1;
    }

    private void show(Player viewer, PlayerNickname current) {
        CommandMessages.sendPanel(viewer, "Your nickname", List.of(
                CommandMessages.Field.normal("Nickname", current.nickname() == null ? "Not set" : current.nickname()),
                CommandMessages.Field.normal("Change", "/nickname <name>"),
                CommandMessages.Field.normal("Clear", "/nickname clear")));
    }

    private void finish(Player viewer, NicknameChangeResult result, Throwable failure) {
        if (failure != null) {
            CommandMessages.send(viewer, CommandMessages.Kind.ERROR,
                    "We couldn't confirm your nickname change. Check /nickname before trying again.");
            return;
        }
        String message = switch (result.status()) {
            case CHANGED -> result.current().nickname() == null
                    ? "Your nickname has been cleared." : "Your nickname is now " + result.current().nickname() + ".";
            case UNCHANGED -> result.current().nickname() == null
                    ? "You don't have a nickname to clear." : "Your nickname is already " + result.current().nickname() + ".";
            case INVALID -> "Use 3–16 letters, digits, or underscores for your nickname.";
            case RESERVED -> "That name is reserved for an account or staff label.";
            case TAKEN -> "Another player already uses that nickname.";
            case STALE -> "Your nickname changed while this request was pending. Check /nickname, then try again.";
            case NOT_FOUND -> "We couldn't find your saved profile. Please reconnect.";
        };
        CommandMessages.Kind kind = switch (result.status()) {
            case CHANGED -> CommandMessages.Kind.SUCCESS;
            case UNCHANGED -> CommandMessages.Kind.INFO;
            default -> CommandMessages.Kind.ERROR;
        };
        CommandMessages.send(viewer, kind, message);
    }
}
