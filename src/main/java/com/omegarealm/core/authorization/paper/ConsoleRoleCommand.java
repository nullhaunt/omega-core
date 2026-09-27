package com.omegarealm.core.authorization.paper;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.omegarealm.core.RuntimeState;
import com.omegarealm.core.authorization.AuthorizationActor;
import com.omegarealm.core.authorization.Role;
import com.omegarealm.core.authorization.RoleChangeRequest;
import com.omegarealm.core.player.PlayerService;
import com.omegarealm.core.scheduler.OmegaScheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.ConsoleCommandSender;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Console authentication and parsing only; no SQL or waiting on Paper's command thread.
 */
public final class ConsoleRoleCommand {

    private final Supplier<ConsoleCommandSender> console;
    private final Supplier<RuntimeState> state;
    private final Supplier<PlayerService> players;
    private final OmegaScheduler scheduler;
    private final Logger logger;
    private final Runnable schedulingFailure;

    public ConsoleRoleCommand(Supplier<ConsoleCommandSender> console, Supplier<RuntimeState> state,
                              Supplier<PlayerService> players, OmegaScheduler scheduler,
                              Logger logger, Runnable schedulingFailure) {
        this.console = Objects.requireNonNull(console, "console");
        this.state = Objects.requireNonNull(state, "state");
        this.players = Objects.requireNonNull(players, "players");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.schedulingFailure = Objects.requireNonNull(schedulingFailure, "schedulingFailure");
    }

    private boolean localConsole(CommandSourceStack source) {
        return source.getSender() == console.get() && source.getExecutor() == null;
    }

    public LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal("omega").requires(this::localConsole)
                .then(Commands.literal("role")
                        .then(Commands.literal("set")
                                .then(Commands.argument("uuid", StringArgumentType.word())
                                        .then(Commands.argument("role", StringArgumentType.word())
                                                .suggests((context, builder) -> {
                                                    for (Role role : Role.values()) {
                                                        if (role.name().startsWith(builder.getRemaining().toUpperCase(Locale.ROOT))) {
                                                            builder.suggest(role.name());
                                                        }
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                        .executes(context -> execute(context.getSource(),
                                                                StringArgumentType.getString(context, "uuid"),
                                                                StringArgumentType.getString(context, "role"),
                                                                StringArgumentType.getString(context, "reason"))))))))
                .build();
    }

    private int execute(CommandSourceStack source, String identity, String role, String reason) {
        if (!localConsole(source)) {
            return 0;
        }
        ConsoleCommandSender sender = console.get();
        RoleChangeRequest request;
        try {
            UUID uuid = UUID.fromString(identity);
            if (!uuid.toString().equalsIgnoreCase(identity)) {
                throw new IllegalArgumentException();
            }
            request = new RoleChangeRequest(new AuthorizationActor.LocalConsole(), uuid,
                    Role.valueOf(role.toUpperCase(Locale.ROOT)), reason);
        } catch (IllegalArgumentException invalid) {
            sender.sendPlainMessage("Use omega role set <canonical UUID> <PLAYER|MODERATOR|ADMIN|OWNER> <reason of 1-512 characters>.");
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
            // Console replies use the owned scheduler; scheduler rejection keeps its normal
            // fail-closed behavior. A committed audit remains durable if shutdown wins.
            try {
                scheduler.global(() -> {
                    if (failure != null) {
                        sender.sendPlainMessage("Role change did not complete successfully; inspect audit history before retrying.");
                        return;
                    }
                    String message = switch (result.status()) {
                        case CHANGED -> "Role changed: target=" + result.authorization().playerUuid()
                                + ", role=" + result.authorization().role().name()
                                + ", revision=" + result.authorization().revision() + ", audit=" + result.auditId() + ".";
                        case UNCHANGED -> "Role unchanged; no revision or audit record was created.";
                        case DENIED -> "Role change was denied by Omega policy.";
                        case NOT_FOUND -> "Target was not found at role lookup; no role change was made.";
                    };
                    sender.sendPlainMessage(message);
                });
            } catch (RejectedExecutionException unavailable) {
                logger.warning("Console role-change reply unavailable; inspect durable audit history.");
                if (state.get() == RuntimeState.READY) {
                    schedulingFailure.run();
                }
            }
        });
        return 1;
    }
}
