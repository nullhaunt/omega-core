package com.omegarealm.core.command.paper;

import com.omegarealm.core.player.PlayerService;
import com.omegarealm.core.scheduler.OmegaScheduler;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Owns Omega's one Paper registration pass and validates its public labels.
 */
public final class OmegaCommands {

    private final RoleCommand roles;
    private final HelpCommand help;
    private volatile Set<String> registered = Set.of();

    public OmegaCommands(CommandAccess access, Supplier<PlayerService> players,
                         OmegaScheduler scheduler, CommandAudit audit, Logger logger,
                         Runnable schedulingFailure, BiConsumer<Player, Runnable> playerReply) {
        Objects.requireNonNull(access, "access");
        roles = new RoleCommand(access, players, scheduler, audit, logger, schedulingFailure, playerReply);
        help = new HelpCommand(access);
    }

    public void register(Commands registrar) {
        Set<String> roleLabels = registrar.register(roles.node(), CommandCatalog.ROLE.description(),
                CommandCatalog.ROLE.aliases());
        Set<String> helpLabels = registrar.register(help.node(), CommandCatalog.HELP.description(),
                CommandCatalog.HELP.aliases());
        if (!roleLabels.containsAll(CommandCatalog.ROLE.labels())
                || !helpLabels.containsAll(CommandCatalog.HELP.labels())) {
            throw new IllegalStateException("Omega command registration did not claim its expected labels.");
        }
        var labels = new HashSet<>(roleLabels);
        labels.addAll(helpLabels);
        registered = Set.copyOf(labels);
    }

    public boolean registered() {
        return registered.containsAll(CommandCatalog.ROLE.labels())
                && registered.containsAll(CommandCatalog.HELP.labels());
    }

    public boolean owns(String label) {
        return registered.contains(label) && CommandCatalog.find(label) != null;
    }
}
