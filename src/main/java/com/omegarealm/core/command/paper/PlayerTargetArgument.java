package com.omegarealm.core.command.paper;

import com.mojang.brigadier.context.CommandContext;
import com.omegarealm.core.player.PlayerTarget;
import io.papermc.paper.command.brigadier.CommandSourceStack;

/**
 * Reads the token parsed by Paper's player-profile argument without resolving it through
 * Paper's profile cache or an external identity service. Omega owns persisted identity lookup.
 */
final class PlayerTargetArgument {

    private PlayerTargetArgument() {
    }

    static PlayerTarget parse(CommandContext<CommandSourceStack> context) {
        for (var parsed : context.getNodes()) {
            if (parsed.getNode().getName().equals("target")) {
                return PlayerTarget.parse(parsed.getRange().get(context.getInput()));
            }
        }

        throw new IllegalStateException("The parsed command has no player target.");
    }
}
