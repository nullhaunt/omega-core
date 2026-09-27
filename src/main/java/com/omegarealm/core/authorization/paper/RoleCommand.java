package com.omegarealm.core.authorization.paper;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.omegarealm.core.RuntimeState;
import com.omegarealm.core.authorization.AuthorizationActor;
import com.omegarealm.core.authorization.AuthorizationSnapshot;
import com.omegarealm.core.authorization.Role;
import com.omegarealm.core.authorization.RoleChangeRequest;
import com.omegarealm.core.player.PlayerService;
import com.omegarealm.core.scheduler.OmegaScheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Authenticates exact platform senders; no SQL or waiting on Paper's command thread.
 */
public final class RoleCommand {

    private final Supplier<ConsoleCommandSender> console;
    private final Supplier<RuntimeState> state;
    private final Supplier<PlayerService> players;
    private final OmegaScheduler scheduler;
    private final Logger logger;
    private final Runnable schedulingFailure;
    private final Function<Player, Optional<AuthorizationSnapshot>> authorization;
    private final BiConsumer<Player, Runnable> playerReply;

    public RoleCommand(Supplier<ConsoleCommandSender> console, Supplier<RuntimeState> state,
                       Supplier<PlayerService> players, OmegaScheduler scheduler,
                       Logger logger, Runnable schedulingFailure,
                       Function<Player, Optional<AuthorizationSnapshot>> authorization,
                       BiConsumer<Player, Runnable> playerReply) {
        this.console = Objects.requireNonNull(console, "console");
        this.state = Objects.requireNonNull(state, "state");
        this.players = Objects.requireNonNull(players, "players");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.schedulingFailure = Objects.requireNonNull(schedulingFailure, "schedulingFailure");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.playerReply = Objects.requireNonNull(playerReply, "playerReply");
    }

    private static boolean canAssign(AuthorizationActor actor, Role requested) {
        return actor instanceof AuthorizationActor.LocalConsole
                || actor instanceof AuthorizationActor.Player player && player.role().isAbove(requested);
    }

    private static String availableRoles(AuthorizationActor actor) {
        return Arrays.stream(Role.values()).filter(role -> canAssign(actor, role))
                .map(Role::name).collect(Collectors.joining("|"));
    }

    private Optional<AuthorizationActor> actor(CommandSourceStack source) {
        if (localConsole(source)) {
            return Optional.of(new AuthorizationActor.LocalConsole());
        }
        if (state.get() == RuntimeState.READY && source.getSender() instanceof Player player
                && source.getExecutor() == player) {
            return authorization.apply(player).filter(snapshot -> snapshot.role().includes(Role.ADMIN))
                    .map(AuthorizationSnapshot::actor);
        }
        return Optional.empty();
    }

    private boolean canAssign(CommandSourceStack source, Role requested) {
        return actor(source).map(actor -> canAssign(actor, requested))
                .orElse(false);
    }

    private boolean localConsole(CommandSourceStack source) {
        return source.getSender() == console.get() && source.getExecutor() == null;
    }

    public LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal("omega").requires(source -> actor(source).isPresent())
                .executes(this::usage)
                .then(Commands.literal("role")
                        .executes(this::usage)
                        .then(Commands.literal("set")
                                .executes(this::usage)
                                .then(Commands.argument("uuid", StringArgumentType.word())
                                        .executes(this::usage)
                                        .then(Commands.argument("role", StringArgumentType.word())
                                                .executes(this::usage)
                                                .suggests((context, builder) -> {
                                                    for (Role role : Role.values()) {
                                                        if (canAssign(context.getSource(), role)
                                                                && role.name().startsWith(builder.getRemaining().toUpperCase(Locale.ROOT))) {
                                                            builder.suggest(role.name());
                                                        }
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                        .executes(this::execute))))))
                .build();
    }

    private AuthorizationActor requireActor(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        var actor = actor(context.getSource());
        if (actor.isPresent()) {
            return actor.get();
        }
        // Cached parse results may outlive authority. Keep genuine access denial in the
        // native parser, including the input/cursor needed to render its error context.
        var reader = new StringReader(context.getInput());
        reader.setCursor(context.getRange().getStart());
        throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().createWithContext(reader);
    }

    private int usage(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AuthorizationActor actor = requireActor(context);
        String prefix = actor instanceof AuthorizationActor.Player ? "/" : "";
        context.getSource().getSender().sendPlainMessage("Usage: " + prefix
                + "omega role set <UUID> <" + availableRoles(actor) + "> <reason>.");
        return 0;
    }

    private int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AuthorizationActor actor = requireActor(context);
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
            sender.sendPlainMessage("Use a canonical UUID (xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx).");
            return 0;
        }

        Role role;
        try {
            role = Role.valueOf(StringArgumentType.getString(context, "role").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            sender.sendPlainMessage("Unknown role. Available roles: " + availableRoles(actor) + ".");
            return 0;
        }

        RoleChangeRequest request;
        try {
            request = new RoleChangeRequest(actor, uuid, role, StringArgumentType.getString(context, "reason"));
        } catch (IllegalArgumentException invalid) {
            sender.sendPlainMessage("A reason of 1-512 characters without control characters is required.");
            return 0;
        }

        // Command access is already established. Explain operation restrictions instead
        // of sending an unknown-command translation with no parser error beneath it.
        if (actor instanceof AuthorizationActor.Player player && player.uuid().equals(uuid)) {
            sender.sendPlainMessage("You cannot change your own role in-game. Use the local console.");
            return 0;
        }
        if (!canAssign(actor, role)) {
            sender.sendPlainMessage("Your role can assign " + availableRoles(actor)
                    + ". Only the local console can assign OWNER.");
            return 0;
        }
        if (state.get() != RuntimeState.READY) {
            sender.sendPlainMessage("Omega Core is unavailable; no role change was accepted.");
            return 0;
        }
        PlayerService service;
        try {
            service = players.get();
        } catch (IllegalStateException unavailable) {
            sender.sendPlainMessage("Omega Core is unavailable; no role change was accepted.");
            return 0;
        }
        service.changeRole(request).whenComplete((result, failure) -> {
            // Replies use the console's global context or this exact connection's entity context.
            // Once accepted, persistence may finish after disconnect; its audit remains durable.
            try {
                Runnable reply = () -> {
                    if (failure != null) {
                        sender.sendPlainMessage("Role change did not complete successfully; inspect audit history before retrying.");
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
                    sender.sendPlainMessage(message);
                };
                if (sender instanceof Player player) {
                    playerReply.accept(player, reply);
                } else {
                    scheduler.global(reply);
                }
            } catch (RuntimeException unavailable) {
                logger.warning("Role-change reply unavailable; inspect durable audit history.");
                if (state.get() == RuntimeState.READY) {
                    schedulingFailure.run();
                }
            }
        });
        return 1;
    }
}
