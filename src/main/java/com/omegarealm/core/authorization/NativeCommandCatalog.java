package com.omegarealm.core.authorization;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Explicit native-tool grants. Parent permissions and wildcard grants are deliberately absent.
 */
public final class NativeCommandCatalog {

    private static final Set<String> WORLD = Set.of("difficulty", "gamerule", "time", "weather",
            "worldborder", "defaultgamemode", "setworldspawn", "whitelist", "save-all", "setidletimeout", "forceload");
    private static final Map<String, String> PAPER = aliases("chunkinfo", "debug", "entity", "fixlight",
            "heap", "holderinfo", "mobcaps", "playermobcaps", "dumpplugins", "dumplisteners", "version", "ver=version");
    private static final Map<String, String> SPARK = aliases("health", "healthreport=health", "profiler",
            "sampler=profiler", "tps", "ping", "gc", "gcmonitor", "tickmonitor", "heapsummary", "heapdump", "activity");
    private static final Map<String, String> SIMPLE = Map.of(
            "version", "bukkit.command.version", "ver", "bukkit.command.version", "about", "bukkit.command.version",
            "plugins", "bukkit.command.plugins", "pl", "bukkit.command.plugins",
            "tps", "bukkit.command.tps", "mspt", "bukkit.command.mspt");

    private NativeCommandCatalog() {
    }

    private static Map<String, String> aliases(String... entries) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String entry : entries) {
            String[] parts = entry.split("=", 2);
            result.put(parts[0], parts[parts.length - 1]);
        }
        return Collections.unmodifiableMap(result);
    }

    public static String root(String label) {
        return label.substring(label.lastIndexOf(':') + 1).toLowerCase(Locale.ROOT);
    }

    public static String canonicalRoot(String label) {
        return switch (root(label)) {
            case "ver", "about" -> "version";
            case "pl" -> "plugins";
            default -> root(label);
        };
    }

    public static String canonicalBranch(String label, String branch) {
        return branches(label).getOrDefault(branch, branch);
    }

    public static boolean reviewed(String label) {
        String root = root(label);
        String namespace = label.contains(":") ? label.substring(0, label.indexOf(':')) : "";
        if (WORLD.contains(root)) {
            return namespace.isEmpty() || namespace.equals("minecraft");
        }
        if (root.equals("paper") || root.equals("spark")) {
            return namespace.isEmpty() || namespace.equals("paper");
        }
        if (!SIMPLE.containsKey(root)) {
            return false;
        }
        return namespace.isEmpty() || namespace.equals(root.equals("tps") ? "spigot" : "bukkit");
    }

    public static Map<String, String> branches(String label) {
        return switch (root(label)) {
            case "paper" -> PAPER;
            case "spark" -> SPARK;
            default -> Map.of();
        };
    }

    public static boolean allows(Role role, String label) {
        return role.includes(Role.ADMIN) && reviewed(label);
    }

    public static Set<String> permissions(Role role) {
        if (!role.includes(Role.ADMIN)) {
            return Set.of();
        }
        Set<String> result = new HashSet<>(SIMPLE.values());
        WORLD.forEach(command -> result.add("minecraft.command." + command));
        PAPER.values().forEach(command -> result.add("bukkit.command.paper." + command));
        SPARK.values().forEach(command -> result.add("spark." + command));
        return Set.copyOf(result);
    }

    /**
     * The same reviewed roots used by the native fence, for the staff help summary.
     */
    public static String helpSummary() {
        Set<String> roots = new TreeSet<>(WORLD);
        roots.addAll(SIMPLE.keySet());
        roots.add("paper");
        roots.add("spark");
        return roots.stream().map(root -> "/" + root).collect(Collectors.joining(", "));
    }
}
