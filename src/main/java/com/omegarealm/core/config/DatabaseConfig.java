package com.omegarealm.core.config;

/**
 * Connection settings only; passwordEnvironment names a variable, never its value.
 */
public record DatabaseConfig(
        String host,
        int port,
        String database,
        String username,
        String passwordEnvironment,
        int poolSize
) {
}
