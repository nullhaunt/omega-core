package com.omegarealm.core.authorization.paper;

import com.omegarealm.core.authorization.NativeCommandCatalog;
import com.omegarealm.core.authorization.Role;
import org.bukkit.permissions.Permission;

import java.util.*;

/**
 * Parents precede descendants so Bukkit's inverted false-child expansion cannot grant a leaf.
 */
final class PermissionPlan {

    private PermissionPlan() {
    }

    static List<String> order(Collection<Permission> permissions) {
        Map<String, Set<String>> graph = new TreeMap<>();
        for (Permission permission : permissions) {
            String name = permission.getName().toLowerCase(Locale.ROOT);
            Set<String> children = new TreeSet<>();
            permission.getChildren().keySet().forEach(child -> children.add(child.toLowerCase(Locale.ROOT)));
            graph.put(name, children);
        }
        Set<String> names = new HashSet<>(graph.keySet());
        graph.values().forEach(names::addAll);
        names.addAll(NativeCommandCatalog.permissions(Role.ADMIN));
        names.forEach(name -> graph.putIfAbsent(name, Set.of()));

        List<String> result = new ArrayList<>();
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String name : graph.keySet()) {
            visit(name, graph, visiting, visited, result);
        }
        Collections.reverse(result);
        return List.copyOf(result);
    }

    private static void visit(String name, Map<String, Set<String>> graph, Set<String> visiting,
                              Set<String> visited, List<String> result) {
        if (visited.contains(name)) {
            return;
        }
        if (!visiting.add(name)) {
            throw new IllegalStateException("Cyclic permission hierarchy cannot be enforced.");
        }
        for (String child : graph.get(name)) {
            visit(child, graph, visiting, visited, result);
        }
        visiting.remove(name);
        visited.add(name);
        result.add(name);
    }
}
