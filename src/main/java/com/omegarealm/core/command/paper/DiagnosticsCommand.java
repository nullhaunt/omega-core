package com.omegarealm.core.command.paper;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.omegarealm.core.RuntimeState;
import com.omegarealm.core.authorization.AuthorizationActor;
import com.omegarealm.core.command.CommandAuditEvent.Outcome;
import com.omegarealm.core.diagnostics.BuildIdentity;
import com.omegarealm.core.diagnostics.RuntimeDiagnostics;
import com.omegarealm.core.player.PlayerServiceDiagnostics;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;

/**
 * Read-only management commands. Their execution needs no I/O or scheduler handoff.
 */
final class DiagnosticsCommand {

    private static final DateTimeFormatter BUILD_TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm 'UTC'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private final CommandAccess access;
    private final BuildIdentity build;
    private final Supplier<RuntimeDiagnostics> runtime;
    private final CommandAudit audit;

    DiagnosticsCommand(CommandAccess access, BuildIdentity build,
                       Supplier<RuntimeDiagnostics> runtime, CommandAudit audit) {
        this.access = Objects.requireNonNull(access, "access");
        this.build = Objects.requireNonNull(build, "build");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    private static String elapsed(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        if (seconds == 0) {
            return "<1s";
        }
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return seconds / 60 + "m " + seconds % 60 + "s";
        }
        if (seconds < 86400) {
            return seconds / 3600 + "h " + seconds % 3600 / 60 + "m";
        }
        return seconds / 86400 + "d " + seconds % 86400 / 3600 + "h";
    }

    private static String observationAge(Duration duration) {
        long millis = Math.max(0, duration.toMillis());
        if (millis < 1000) {
            return "<1s";
        }
        if (millis >= 10000) {
            return elapsed(duration);
        }

        long tenths = (millis + 50) / 100;
        if (tenths % 10 == 0) {
            return tenths / 10 + "s";
        }
        return tenths / 10 + "." + tenths % 10 + "s";
    }

    private static CommandMessages.Field stateField(RuntimeState state) {
        return switch (state) {
            case READY -> CommandMessages.Field.good("State", "Ready");
            case STARTING -> CommandMessages.Field.warning("State", "Starting");
            case STOPPING -> CommandMessages.Field.warning("State", "Stopping");
            case STOPPED -> CommandMessages.Field.warning("State", "Stopped");
            case FAILED -> CommandMessages.Field.error("State", "Failed");
        };
    }

    LiteralCommandNode<CommandSourceStack> buildNode() {
        return Commands.literal(CommandCatalog.BUILD.name())
                .requires(source -> access.allows(CommandCatalog.BUILD, source))
                .executes(this::showBuild)
                .build();
    }

    LiteralCommandNode<CommandSourceStack> statusNode() {
        return Commands.literal(CommandCatalog.STATUS.name())
                .requires(source -> access.allows(CommandCatalog.STATUS, source))
                .executes(this::showStatus)
                .build();
    }

    private int showBuild(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AuthorizationActor actor = access.require(CommandCatalog.BUILD, context);
        audit.record(actor, CommandCatalog.BUILD.category(), "build", Outcome.INVOKED,
                UUID.randomUUID(), null);

        CommandMessages.sendPanel(context.getSource().getSender(), "BUILD", List.of(
                CommandMessages.Field.normal("Version", build.version()),
                CommandMessages.Field.normal("Revision", build.commit()),
                CommandMessages.Field.normal("Built", BUILD_TIME.format(build.builtAt()))));
        return 1;
    }

    private int showStatus(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        AuthorizationActor actor = access.require(CommandCatalog.STATUS, context);
        audit.record(actor, CommandCatalog.STATUS.category(), "status", Outcome.INVOKED,
                UUID.randomUUID(), null);

        RuntimeDiagnostics snapshot = runtime.get();
        CommandSender sender = context.getSource().getSender();
        List<CommandMessages.Field> fields = new ArrayList<>();
        fields.add(stateField(snapshot.state()));
        fields.add(CommandMessages.Field.normal("Server",
                snapshot.serverId() + " · " + snapshot.environment()));
        fields.add(CommandMessages.Field.normal("Uptime", elapsed(snapshot.runtimeAge())));

        if (snapshot.players().isEmpty()) {
            fields.add(CommandMessages.Field.warning("Player service", "Not initialized"));
            fields.add(CommandMessages.Field.warning("Database", "No completed check yet"));
            CommandMessages.sendPanel(sender, "STATUS", fields);
            return 1;
        }

        PlayerServiceDiagnostics players = snapshot.players().get();
        if (!players.running()) {
            fields.add(CommandMessages.Field.error("Player service", "Unavailable"));
        }
        fields.add(CommandMessages.Field.normal("Players",
                players.connectedAttempts() + " connected · "
                        + players.trackedOpenSessions() + " open sessions"));
        fields.add(CommandMessages.Field.normal("Pending I/O",
                Integer.toString(players.pendingOperations())));

        String ownership = players.ownershipVerifiedAgo()
                .map(DiagnosticsCommand::observationAge)
                .map(value -> "Last check " + value + " ago: connected, server lock held")
                .orElse("No completed check yet");
        if (players.ownershipCheckPending()) {
            ownership += " · new check pending";
        }
        fields.add(players.ownershipVerifiedAgo().isPresent()
                ? CommandMessages.Field.normal("Database", ownership)
                : CommandMessages.Field.warning("Database", ownership));

        String authorization = players.oldestAuthorizationAge()
                .map(DiagnosticsCommand::observationAge)
                .map(value -> "oldest check " + value + " ago")
                .orElse("No connected observations");
        int expired = players.expiredAuthorizations();
        authorization += " · " + expired + (expired == 1 ? " expired connection" : " expired connections");
        fields.add(expired == 0
                ? CommandMessages.Field.normal("Authorization", authorization)
                : CommandMessages.Field.warning("Authorization", authorization));

        CommandMessages.sendPanel(sender, "STATUS", fields);
        return 1;
    }
}
