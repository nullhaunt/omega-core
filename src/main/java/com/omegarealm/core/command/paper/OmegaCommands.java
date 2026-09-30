package com.omegarealm.core.command.paper;

import com.mojang.brigadier.tree.LiteralCommandNode;
import com.omegarealm.core.diagnostics.BuildIdentity;
import com.omegarealm.core.diagnostics.RuntimeDiagnostics;
import com.omegarealm.core.player.PlayerService;
import com.omegarealm.core.player.paper.ProfileDialog;
import com.omegarealm.core.scheduler.OmegaScheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Owns Omega's one Paper registration pass and validates its public labels.
 */
public final class OmegaCommands {

    private final RoleCommand roles;
    private final ProfileCommand profiles;
    private final NicknameCommand nicknames;
    private final RealnameCommand realnames;
    private final HelpCommand help;
    private final DiagnosticsCommand diagnostics;
    private volatile Set<String> registered = Set.of();

    public OmegaCommands(CommandAccess access, Supplier<PlayerService> players,
                         BuildIdentity build, Supplier<RuntimeDiagnostics> runtime,
                         OmegaScheduler scheduler, CommandAudit audit, Logger logger,
                         Runnable schedulingFailure, BiConsumer<Player, Runnable> playerReply,
                         Function<Player, Optional<UUID>> connectionToken) {
        Objects.requireNonNull(access, "access");
        PlayerTargetSuggestions targetSuggestions =
                new PlayerTargetSuggestions(access, prefix -> players.get().suggestTargets(prefix));
        CommandReplies replies = new CommandReplies(access, scheduler, playerReply, logger, schedulingFailure);
        roles = new RoleCommand(access, players, scheduler, audit, logger,
                schedulingFailure, playerReply, targetSuggestions);
        profiles = new ProfileCommand(access, players::get, playerReply,
                ProfileDialog::show, logger, schedulingFailure, targetSuggestions);
        nicknames = new NicknameCommand(access, players, connectionToken, replies);
        realnames = new RealnameCommand(access, players::get, replies, targetSuggestions);
        help = new HelpCommand(access);
        diagnostics = new DiagnosticsCommand(access, build, runtime, audit);
    }

    public void register(Commands registrar) {
        registered = Set.of();

        // The native type supplies player completion to clients. Omega still resolves the
        // typed name or UUID from its own persisted player records during execution.
        var targetType = ArgumentTypes.playerProfiles();

        List<Registration> commands = List.of(
                new Registration(CommandCatalog.HELP, help.node()),
                new Registration(CommandCatalog.PROFILE, profiles.node(targetType)),
                new Registration(CommandCatalog.NICKNAME, nicknames.node()),
                new Registration(CommandCatalog.REALNAME, realnames.node(targetType)),
                new Registration(CommandCatalog.BUILD, diagnostics.buildNode()),
                new Registration(CommandCatalog.STATUS, diagnostics.statusNode()),
                new Registration(CommandCatalog.ROLE, roles.node(targetType)));
        List<CommandCatalog.Definition> registeredDefinitions = commands.stream()
                .map(Registration::definition)
                .toList();
        Set<CommandCatalog.Definition> definitions = new HashSet<>(registeredDefinitions);
        if (definitions.size() != commands.size()
                || !definitions.equals(Set.copyOf(CommandCatalog.definitions()))) {
            throw new IllegalStateException("Omega command registration does not match its catalog.");
        }

        var labels = new HashSet<String>();
        for (Registration command : commands) {
            CommandCatalog.Definition definition = command.definition();
            Set<String> claimed = registrar.register(command.node(),
                    definition.description(), definition.aliases());
            if (!claimed.containsAll(definition.labels())) {
                throw new IllegalStateException("Omega command registration did not claim its expected labels.");
            }
            labels.addAll(claimed);
        }
        registered = Set.copyOf(labels);
    }

    public boolean registered() {
        for (CommandCatalog.Definition definition : CommandCatalog.definitions()) {
            if (!registered.containsAll(definition.labels())) {
                return false;
            }
        }
        return true;
    }

    public boolean owns(String label) {
        return registered.contains(label) && CommandCatalog.find(label) != null;
    }

    private record Registration(CommandCatalog.Definition definition,
                                LiteralCommandNode<CommandSourceStack> node) {

        private Registration {
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(node, "node");
            if (!definition.name().equals(node.getLiteral())) {
                throw new IllegalArgumentException("A command node must match its catalog name.");
            }
        }
    }
}
