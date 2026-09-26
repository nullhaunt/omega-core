package com.omegarealm.core;

import com.omegarealm.core.config.ConfigException;
import com.omegarealm.core.config.ConfigLoader;
import com.omegarealm.core.config.OmegaConfig;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Owns the application lifetime. No database or gameplay services are started yet.
 */
public final class OmegaRuntime {

    private final OmegaConfig config;
    private final Logger logger;
    private boolean stopped;

    OmegaRuntime(OmegaConfig config, Logger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public static OmegaRuntime start(JavaPlugin plugin) throws ConfigException {
        OmegaConfig config = new ConfigLoader().load(plugin.getDataFolder().toPath());
        OmegaRuntime runtime = new OmegaRuntime(config, plugin.getLogger());

        plugin.getLogger().info("Omega Core foundation started: server=" + config.server().id()
                + ", environment=" + config.server().environment()
                + ". Configuration validated; database services are not initialized.");

        return runtime;
    }

    public OmegaConfig config() {
        return config;
    }

    /**
     * Idempotent; future owned resources will be closed here in reverse startup order.
     */
    public synchronized void stop() {
        if (stopped) {
            return;
        }

        stopped = true;
        logger.info("Omega Core foundation stopped.");
    }
}
