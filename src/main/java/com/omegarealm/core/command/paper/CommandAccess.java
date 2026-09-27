package com.omegarealm.core.command.paper;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.omegarealm.core.RuntimeState;
import com.omegarealm.core.authorization.AuthorizationActor;
import com.omegarealm.core.authorization.AuthorizationSnapshot;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Exact sender authentication shared by parsing, execution, help and native fencing.
 */
public final class CommandAccess {

    private final Supplier<ConsoleCommandSender> console;
    private final Supplier<RuntimeState> state;
    private final Function<Player, Optional<AuthorizationSnapshot>> authorization;

    public CommandAccess(Supplier<ConsoleCommandSender> console, Supplier<RuntimeState> state,
                         Function<Player, Optional<AuthorizationSnapshot>> authorization) {
        this.console = Objects.requireNonNull(console, "console");
        this.state = Objects.requireNonNull(state, "state");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
    }

    public Optional<AuthorizationActor> actor(CommandSourceStack source) {
        if (source.getSender() == console.get() && source.getExecutor() == null) {
            return Optional.of(new AuthorizationActor.LocalConsole());
        }
        if (state.get() == RuntimeState.READY && source.getSender() instanceof Player player
                && source.getExecutor() == player) {
            return authorization.apply(player).map(AuthorizationSnapshot::actor);
        }
        return Optional.empty();
    }

    public boolean ready() {
        return state.get() == RuntimeState.READY;
    }

    public boolean allows(CommandCatalog.Definition definition, CommandSourceStack source) {
        return actor(source).map(actor -> actor instanceof AuthorizationActor.LocalConsole
                || actor instanceof AuthorizationActor.Player player
                && player.role().includes(definition.minimumRole())).orElse(false);
    }

    public AuthorizationActor require(CommandCatalog.Definition definition,
                                      CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Optional<AuthorizationActor> current = actor(context.getSource());
        if (current.isPresent() && (current.get() instanceof AuthorizationActor.LocalConsole
                || current.get() instanceof AuthorizationActor.Player player
                && player.role().includes(definition.minimumRole()))) {
            return current.get();
        }
        // A parse result may outlive the player's authority. Preserve native error context.
        var reader = new StringReader(context.getInput());
        reader.setCursor(context.getRange().getStart());
        throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().createWithContext(reader);
    }
}
