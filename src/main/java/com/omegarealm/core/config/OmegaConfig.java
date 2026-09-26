package com.omegarealm.core.config;

/**
 * Immutable settings produced by ConfigLoader after validation. Contains no secrets.
 */
public record OmegaConfig(ServerConfig server, DatabaseConfig database, boolean debug) {
}
