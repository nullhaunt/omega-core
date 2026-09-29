package com.omegarealm.core.command.paper;

import com.omegarealm.core.authorization.AuthorizationActor;
import com.omegarealm.core.authorization.Role;
import com.omegarealm.core.command.CommandAuditEvent.Category;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One source for Omega command names, descriptions, access and help.
 */
public final class CommandCatalog {

    public static final String NAMESPACE = "omegacore";

    public static final Definition HELP = new Definition("help", "help [command]",
            "Find available commands", Role.PLAYER, null, List.of(), false);
    public static final Definition BUILD = new Definition("build", "build",
            "See the Omega version and build time", Role.ADMIN, Category.MANAGEMENT, List.of(), false);
    public static final Definition STATUS = new Definition("status", "status",
            "Check the server's status", Role.ADMIN, Category.MANAGEMENT, List.of(), false);
    public static final Definition ROLE = new Definition("role", "role set <player> <role> <reason>",
            "Change a player's role", Role.ADMIN, Category.MANAGEMENT, List.of(), false);
    public static final Definition PROFILE = new Definition("profile", "profile [player]",
            "View a player's profile", Role.PLAYER, null, List.of(), true);

    private static final List<Definition> DEFINITIONS = List.of(HELP, PROFILE, BUILD, STATUS, ROLE);
    private static final Map<String, Definition> LABELS = DEFINITIONS.stream()
            .flatMap(definition -> definition.labels().stream().map(label -> Map.entry(label, definition)))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    private CommandCatalog() {
    }

    public static List<Definition> definitions() {
        return DEFINITIONS;
    }

    public static Definition find(String label) {
        return LABELS.get(label);
    }

    public static String roleName(Role role) {
        return switch (role) {
            case PLAYER -> "Player";
            case MODERATOR -> "Moderator";
            case ADMIN -> "Admin";
            case OWNER -> "Owner";
        };
    }

    public static boolean canAssign(AuthorizationActor actor, Role requested) {
        return actor instanceof AuthorizationActor.LocalConsole
                || actor instanceof AuthorizationActor.Player player && player.role().isAbove(requested);
    }

    public record Definition(String name, String syntax, String description, Role minimumRole,
                             Category category, List<String> aliases, boolean playersOnly) {

        public Definition {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("A command name is required.");
            }
            if (syntax == null || !(syntax.equals(name) || syntax.startsWith(name + " "))) {
                throw new IllegalArgumentException("Command syntax must begin with its name.");
            }
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("A command description is required.");
            }
            minimumRole = Objects.requireNonNull(minimumRole, "minimumRole");
            aliases = List.copyOf(aliases);
        }

        public String usage(AuthorizationActor actor) {
            String prefix = actor instanceof AuthorizationActor.Player ? "/" : "";
            return prefix + syntax;
        }

        public Set<String> labels() {
            var labels = new java.util.HashSet<String>();
            labels.add(name);
            labels.add(NAMESPACE + ":" + name);
            for (String alias : aliases) {
                labels.add(alias);
                labels.add(NAMESPACE + ":" + alias);
            }
            return Set.copyOf(labels);
        }
    }
}
