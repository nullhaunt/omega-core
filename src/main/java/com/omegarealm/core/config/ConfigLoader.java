package com.omegarealm.core.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Reads a complete configuration without default merging or implicit type conversion.
 */
public final class ConfigLoader {

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "server", "server.id", "server.environment",
            "database", "database.host", "database.port", "database.database",
            "database.username", "database.password-env", "database.pool-size", "debug"
    );

    private static String text(YamlConfiguration yaml, String key) throws ConfigException {
        Object value = yaml.get(key);
        if (!(value instanceof String text) || text.isBlank() || !text.equals(text.strip())
                || text.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid(key, "must be a nonblank string without surrounding whitespace or control characters");
        }

        return text;
    }

    private static int integer(YamlConfiguration yaml, String key, int minimum, int maximum)
            throws ConfigException {
        Object value = yaml.get(key);
        if (!(value instanceof Integer number) || number < minimum || number > maximum) {
            throw invalid(key, "must be a YAML integer between " + minimum + " and " + maximum);
        }

        return number;
    }

    private static ConfigException invalid(String key, String requirement) {
        return new ConfigException("config.yml: " + key + " " + requirement + ".");
    }

    /**
     * Bootstrap-only filesystem work, before any gameplay services are registered.
     */
    public OmegaConfig load(Path dataDirectory) throws ConfigException {
        Path file = dataDirectory.resolve("config.yml");

        try {
            if (Files.notExists(file)) {
                Files.createDirectories(dataDirectory);

                try (InputStream defaults = ConfigLoader.class.getResourceAsStream("/config.yml")) {
                    if (defaults == null) {
                        throw new ConfigException("config.yml: bundled defaults are missing.");
                    }

                    Files.copy(defaults, file);
                }
            }

            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            // Do not forward external exception messages, which may include sensitive input.
            throw new ConfigException("config.yml: unable to create or read the configuration file ("
                    + exception.getClass().getSimpleName() + "). Check the plugin data directory and permissions.");
        }
    }

    public OmegaConfig parse(String contents) throws ConfigException {
        YamlConfiguration yaml = new YamlConfiguration();

        try {
            yaml.loadFromString(contents);
        } catch (InvalidConfigurationException | YAMLException exception) {
            // YAML parser exceptions can contain source excerpts, including credentials.
            throw new ConfigException("config.yml: invalid YAML; check syntax and indentation.");
        }

        for (String key : yaml.getKeys(true)) {
            if (!ALLOWED_KEYS.contains(key)) {
                // Unknown names are also input; do not echo a possibly sensitive name.
                String section = key.startsWith("server.") ? "server"
                        : key.startsWith("database.") ? "database" : "the root";
                throw new ConfigException("config.yml: unknown setting under " + section
                        + "; compare keys with the bundled config.yml.");
            }
        }

        String serverId = text(yaml, "server.id");
        if (!serverId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw invalid("server.id", "must contain 1-64 letters, digits, underscores or hyphens, starting with a letter or digit");
        }

        String environment = text(yaml, "server.environment");
        if (!environment.matches("[a-z][a-z0-9-]{0,31}")) {
            throw invalid("server.environment", "must contain 1-32 lowercase letters, digits or hyphens, starting with a letter");
        }

        String host = text(yaml, "database.host");
        int port = integer(yaml, "database.port", 1, 65535);
        String database = text(yaml, "database.database");
        String username = text(yaml, "database.username");
        String passwordEnvironment = text(yaml, "database.password-env");

        if (!passwordEnvironment.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw invalid("database.password-env", "must be an environment-variable name containing letters, digits or underscores, starting with a letter or underscore");
        }

        int poolSize = integer(yaml, "database.pool-size", 1, Integer.MAX_VALUE);
        Object debug = yaml.get("debug");
        if (!(debug instanceof Boolean debugEnabled)) {
            throw invalid("debug", "must be a YAML boolean (true or false)");
        }

        return new OmegaConfig(
                new ServerConfig(serverId, environment),
                new DatabaseConfig(host, port, database, username, passwordEnvironment, poolSize),
                debugEnabled
        );
    }
}
