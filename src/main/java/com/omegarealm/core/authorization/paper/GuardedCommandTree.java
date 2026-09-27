package com.omegarealm.core.authorization.paper;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Copies handlers and argument types, guarding parsing, execution and server-side suggestions.
 */
final class GuardedCommandTree<S> {

    private final Predicate<S> allowed;
    private final Consumer<CommandContext<S>> invoked;
    private final Map<CommandNode<S>, CommandNode<S>> copied = new IdentityHashMap<>();
    private final Set<CommandNode<S>> visiting = Collections.newSetFromMap(new IdentityHashMap<>());

    GuardedCommandTree(Predicate<S> allowed, Consumer<CommandContext<S>> invoked) {
        this.allowed = allowed;
        this.invoked = invoked;
    }

    LiteralCommandNode<S> copy(LiteralCommandNode<S> original) {
        return (LiteralCommandNode<S>) copyNode(original);
    }

    private CommandNode<S> copyNode(CommandNode<S> original) {
        CommandNode<S> existing = copied.get(original);
        if (existing != null) {
            return existing;
        }
        if (!visiting.add(original)) {
            throw new IllegalStateException("Reviewed command has an unsupported cyclic redirect.");
        }
        CommandNode<S> redirect = original.getRedirect() == null ? null : copyNode(original.getRedirect());
        Predicate<S> requirement = source -> allowed.test(source) && original.canUse(source);
        Command<S> command = guard(original.getCommand());
        CommandNode<S> result;
        if (original instanceof LiteralCommandNode<S> literal) {
            result = literal(literal.getLiteral(), command, requirement,
                    redirect, original.getRedirectModifier(), original.isFork());
        } else if (original instanceof ArgumentCommandNode<S, ?> argument) {
            result = copyArgument(argument, command, requirement, redirect);
        } else {
            throw new IllegalStateException("Reviewed command has an unsupported node.");
        }
        copied.put(original, result);
        for (CommandNode<S> child : original.getChildren()) {
            result.addChild(copyNode(child));
        }
        visiting.remove(original);
        return result;
    }

    private <T> ArgumentCommandNode<S, T> copyArgument(ArgumentCommandNode<S, T> original,
                                                       Command<S> command, Predicate<S> requirement, CommandNode<S> redirect) {
        SuggestionProvider<S> suggestions = original.getCustomSuggestions();
        return new ArgumentCommandNode<>(original.getName(), original.getType(), command, requirement,
                redirect, original.getRedirectModifier(), original.isFork(), suggestions == null ? null
                : (context, builder) -> allowed.test(context.getSource())
                ? suggestions.getSuggestions(context, builder) : builder.buildFuture()) {
            @Override
            public CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder)
                    throws CommandSyntaxException {
                return allowed.test(context.getSource()) ? super.listSuggestions(context, builder) : builder.buildFuture();
            }
        };
    }

    LiteralCommandNode<S> literal(String label, Command<S> command, Predicate<S> requirement,
                                  CommandNode<S> redirect, com.mojang.brigadier.RedirectModifier<S> modifier, boolean fork) {
        return new LiteralCommandNode<>(label, command, requirement, redirect, modifier, fork) {
            @Override
            public CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
                return allowed.test(context.getSource()) && requirement.test(context.getSource())
                        ? super.listSuggestions(context, builder) : builder.buildFuture();
            }
        };
    }

    private Command<S> guard(Command<S> command) {
        if (command == null) {
            return null;
        }
        return context -> {
            check(context);
            invoked.accept(context);
            return command.run(context);
        };
    }

    private void check(CommandContext<S> context) throws CommandSyntaxException {
        if (!allowed.test(context.getSource())) {
            var reader = new StringReader(context.getInput());
            reader.setCursor(context.getRange().getStart());
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().createWithContext(reader);
        }
    }

    /**
     * Legacy Paper/spark have a greedy argument. Give players a literal branch for each reviewed tool.
     */
    LiteralCommandNode<S> branches(LiteralCommandNode<S> original, Set<String> branches, Predicate<S> player) {
        LiteralCommandNode<S> root = literal(original.getLiteral(), guard(original.getCommand()),
                source -> allowed.test(source) && original.canUse(source), null, null, false);
        @SuppressWarnings("unchecked")
        ArgumentCommandNode<S, String> arguments = (ArgumentCommandNode<S, String>) original.getChild("args");
        if (arguments == null) {
            throw new IllegalStateException("Reviewed legacy command has no argument adapter.");
        }
        // The original greedy branch remains available to console, preserving its full tool set.
        root.addChild(RequiredArgumentBuilder.<S, String>argument("args", StringArgumentType.greedyString())
                .requires(source -> !player.test(source))
                .suggests((context, builder) -> player.test(context.getSource()) ? builder.buildFuture()
                        : arguments.getCustomSuggestions().getSuggestions(context, builder))
                .executes(guard(arguments.getCommand())).build());
        for (String branch : branches) {
            LiteralCommandNode<S> tool = literal(branch, guard(arguments.getCommand()), allowed, null, null, false);
            tool.addChild(RequiredArgumentBuilder.<S, String>argument("args", StringArgumentType.greedyString())
                    .suggests((context, builder) -> {
                        if (!allowed.test(context.getSource())) {
                            return builder.buildFuture();
                        }
                        // Bukkit's completer expects the complete argument list, including the tool name.
                        return arguments.getCustomSuggestions().getSuggestions(context,
                                builder.createOffset(builder.getInput().indexOf(' ') + 1));
                    }).executes(guard(arguments.getCommand())).build());
            root.addChild(tool);
        }
        return root;
    }
}
