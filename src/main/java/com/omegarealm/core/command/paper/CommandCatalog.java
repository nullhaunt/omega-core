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

    public static final Definition HELP = new Definition("help", "Show available commands", Role.PLAYER,
            null, List.of());
    public static final Definition BUILD = new Definition("build", "Show the deployed Omega build identity",
            Role.ADMIN, Category.MANAGEMENT, List.of());
    public static final Definition STATUS = new Definition("status", "Show Omega runtime observations",
            Role.ADMIN, Category.MANAGEMENT, List.of());
    public static final Definition ROLE = new Definition("role", "Manage realm-wide player roles", Role.ADMIN,
            Category.MANAGEMENT, List.of());

    private static final List<Definition> DEFINITIONS = List.of(HELP, BUILD, STATUS, ROLE);
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

    public static String availableRoles(AuthorizationActor actor) {
        return Arrays.stream(Role.values()).filter(role -> canAssign(actor, role))
                .map(Role::name).collect(Collectors.joining("|"));
    }

    public static boolean canAssign(AuthorizationActor actor, Role requested) {
        return actor instanceof AuthorizationActor.LocalConsole
                || actor instanceof AuthorizationActor.Player player && player.role().isAbove(requested);
    }

    public static String usage(Definition definition, AuthorizationActor actor) {
        String prefix = actor instanceof AuthorizationActor.Player ? "/" : "";
        if (definition == ROLE) {
            return prefix + "role set <username|UUID> <" + availableRoles(actor) + "> <reason...>";
        }
        if (definition == BUILD || definition == STATUS) {
            return prefix + definition.name();
        }
        return prefix + "help [command].";
    }

    public record Definition(String name, String description, Role minimumRole,
                             Category category, List<String> aliases) {

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
