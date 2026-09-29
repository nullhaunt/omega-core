package com.omegarealm.core.command.paper;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.omegarealm.core.authorization.AuthorizationActor;
import com.omegarealm.core.authorization.NativeCommandCatalog;
import com.omegarealm.core.authorization.Role;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Help derives its visible custom commands from the same definitions used by execution.
 */
final class HelpCommand {

    private final CommandAccess access;

    HelpCommand(CommandAccess access) {
        this.access = access;
    }

    LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal(CommandCatalog.HELP.name())
                .requires(source -> access.allows(CommandCatalog.HELP, source))
                .executes(this::list)
                .then(Commands.argument("command", StringArgumentType.greedyString())
                        .suggests((context, builder) -> {
                            for (CommandCatalog.Definition definition : CommandCatalog.definitions()) {
                                if (access.allows(definition, context.getSource())
                                        && definition.name().startsWith(builder.getRemaining().toLowerCase(Locale.ROOT))) {
                                    builder.suggest(definition.name());
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(this::detail))
                .build();
    }

    private int list(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AuthorizationActor actor = access.require(CommandCatalog.HELP, context);
        var sender = context.getSource().getSender();
        List<CommandMessages.Field> fields = new ArrayList<>();
        for (CommandCatalog.Definition definition : CommandCatalog.definitions()) {
            if (access.allows(definition, context.getSource())) {
                fields.add(CommandMessages.Field.normal("/" + definition.name(), definition.description()));
            }
        }
        if (actor instanceof AuthorizationActor.LocalConsole
                || actor instanceof AuthorizationActor.Player player && player.role().includes(Role.ADMIN)) {
            fields.add(CommandMessages.Field.normal("Server tools", NativeCommandCatalog.helpSummary()));
        }
        CommandMessages.sendPanel(sender, "Available commands", fields);
        return 1;
    }

    private int detail(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AuthorizationActor actor = access.require(CommandCatalog.HELP, context);
        String requested = StringArgumentType.getString(context, "command");
        CommandCatalog.Definition definition = CommandCatalog.find(requested);
        if (definition == null || !access.allows(definition, context.getSource())) {
            var reader = new StringReader(context.getInput());
            reader.setCursor(context.getNodes().getLast().getRange().getStart());
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().createWithContext(reader);
        }
        CommandMessages.sendPanel(context.getSource().getSender(), definition.description(), List.of(
                CommandMessages.Field.normal("Command", definition.usage(actor))));
        return 1;
    }
}
