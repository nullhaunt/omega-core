package com.omegarealm.core.command.paper;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Offers connected names without changing the persisted name/UUID target contract.
 */
final class OnlinePlayerTargetSuggestions {

    private final CommandAccess access;
    private final Supplier<? extends Collection<? extends Player>> onlinePlayers;

    OnlinePlayerTargetSuggestions(CommandAccess access,
                                  Supplier<? extends Collection<? extends Player>> onlinePlayers) {
        this.access = Objects.requireNonNull(access, "access");
        this.onlinePlayers = Objects.requireNonNull(onlinePlayers, "onlinePlayers");
    }

    CompletableFuture<Suggestions> suggest(CommandCatalog.Definition command,
                                           CommandContext<CommandSourceStack> context,
                                           SuggestionsBuilder builder) {
        if (!access.allows(command, context.getSource())) {
            return builder.buildFuture();
        }

        String prefix = builder.getRemainingLowerCase();
        onlinePlayers.get().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(builder::suggest);
        return builder.buildFuture();
    }
}
