package com.omegarealm.core.command.paper;

import com.omegarealm.core.authorization.AuthorizationActor;
import com.omegarealm.core.authorization.Role;
import com.omegarealm.core.command.CommandAuditEvent.Category;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One source for Omega command names, descriptions, access and help.
 */
public final class CommandCatalog {

    public static final String NAMESPACE = "omegacore";

    public static final Definition HELP = new Definition("help", "Find available commands", Role.PLAYER,
            null, List.of(), false);
    public static final Definition BUILD = new Definition("build", "See the Omega version and build time",
            Role.ADMIN, Category.MANAGEMENT, List.of(), false);
    public static final Definition STATUS = new Definition("status", "Check the server's status",
            Role.ADMIN, Category.MANAGEMENT, List.of(), false);
    public static final Definition ROLE = new Definition("role", "Change a player's role", Role.ADMIN,
            Category.MANAGEMENT, List.of(), false);
    public static final Definition PROFILE = new Definition("profile", "View a player's profile",
            Role.PLAYER, null, List.of(), true);

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

    public static String assignableRoleNames(AuthorizationActor actor) {
        List<String> names = Arrays.stream(Role.values())
                .filter(role -> canAssign(actor, role))
                .map(CommandCatalog::roleName)
                .toList();
        if (names.isEmpty()) {
            return "none";
        }
        if (names.size() == 1) {
            return names.getFirst();
        }
        if (names.size() == 2) {
            return names.getFirst() + " or " + names.getLast();
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + ", or " + names.getLast();
    }

    public static boolean canAssign(AuthorizationActor actor, Role requested) {
        return actor instanceof AuthorizationActor.LocalConsole
                || actor instanceof AuthorizationActor.Player player && player.role().isAbove(requested);
    }

    public static String usage(Definition definition, AuthorizationActor actor) {
        String prefix = actor instanceof AuthorizationActor.Player ? "/" : "";
        if (definition == ROLE) {
            return prefix + "role set <player> <role> <reason>";
        }
        if (definition == PROFILE) {
            return prefix + "profile [player]";
        }
        if (definition == BUILD || definition == STATUS) {
            return prefix + definition.name();
        }
        return prefix + "help [command]";
    }

    public record Definition(String name, String description, Role minimumRole,
                             Category category, List<String> aliases, boolean playersOnly) {

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
