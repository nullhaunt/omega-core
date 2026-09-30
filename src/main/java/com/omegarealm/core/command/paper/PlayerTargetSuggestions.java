package com.omegarealm.core.command.paper;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Bounded stored-name hints. Execution still resolves identity and rechecks access.
 */
final class PlayerTargetSuggestions {

    private final CommandAccess access;
    private final Function<String, CompletionStage<List<String>>> names;

    PlayerTargetSuggestions(CommandAccess access, Function<String, CompletionStage<List<String>>> names) {
        this.access = Objects.requireNonNull(access, "access");
        this.names = Objects.requireNonNull(names, "names");
    }

    CompletableFuture<Suggestions> suggest(CommandCatalog.Definition command,
                                           CommandContext<CommandSourceStack> context,
                                           SuggestionsBuilder builder) {
        if (!access.allows(command, context.getSource()) || !access.ready()) {
            return builder.buildFuture();
        }
        CompletionStage<List<String>> pending;
        try {
            pending = names.apply(builder.getRemaining());
        } catch (IllegalStateException unavailable) {
            return builder.buildFuture();
        }
        // The continuation touches only immutable names and Brigadier's private builder.
        // Failure/saturation omits optional hints; it never falls back to another identity source.
        return pending.handle((matches, failure) -> {
            if (failure == null) {
                matches.forEach(builder::suggest);
            }
            return builder.build();
        }).toCompletableFuture();
    }
}
