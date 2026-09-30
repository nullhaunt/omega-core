package com.omegarealm.core.command.paper;

import com.omegarealm.core.scheduler.OmegaScheduler;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * Dispatches asynchronous command replies to the exact connection or local console.
 */
final class CommandReplies {

    private final CommandAccess access;
    private final OmegaScheduler scheduler;
    private final BiConsumer<Player, Runnable> playerReply;
    private final Logger logger;
    private final Runnable schedulingFailure;

    CommandReplies(CommandAccess access, OmegaScheduler scheduler, BiConsumer<Player, Runnable> playerReply,
                   Logger logger, Runnable schedulingFailure) {
        this.access = Objects.requireNonNull(access, "access");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.playerReply = Objects.requireNonNull(playerReply, "playerReply");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.schedulingFailure = Objects.requireNonNull(schedulingFailure, "schedulingFailure");
    }

    void dispatch(CommandSender sender, Runnable action) {
        try {
            if (sender instanceof Player player) {
                // The adapter drops expired or replaced connections on their entity context.
                playerReply.accept(player, action);
            } else {
                scheduler.global(() -> {
                    if (access.ready()) {
                        action.run();
                    }
                });
            }
        } catch (RuntimeException unavailable) {
            logger.warning("Command reply unavailable; inspect audit history before retrying an edit.");
            if (access.ready()) {
                schedulingFailure.run();
            }
        }
    }
}
