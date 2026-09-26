package com.omegarealm.core;

import com.omegarealm.core.config.ConfigException;
import com.omegarealm.core.config.ConfigLoader;
import com.omegarealm.core.config.OmegaConfig;
import com.omegarealm.core.database.PostgresBootstrap;
import com.omegarealm.core.lifecycle.AdmissionGuard;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class OmegaPlugin extends JavaPlugin {

    private final AtomicBoolean shutdownRequested = new AtomicBoolean();
    private volatile OmegaRuntime runtime;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new AdmissionGuard(this::state), this);

        try {
            OmegaConfig config = new ConfigLoader().load(getDataFolder().toPath());
            PostgresBootstrap database = new PostgresBootstrap(config.database(), System::getenv,
                    getLogger(), getClass().getClassLoader());
            runtime = new OmegaRuntime(config, getLogger(), database,
                    task -> getServer().getGlobalRegionScheduler().execute(this, task), this::stopServer);
            runtime.start();
        } catch (ConfigException exception) {
            getLogger().severe("Omega Core startup failed: " + exception.getMessage());
            stopServer();
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Unexpected Omega Core startup failure.", exception);
            stopServer();
        }
    }

    @Override
    public void onDisable() {
        if (!getServer().isStopping()) {
            stopServer();
        }

        if (runtime != null) {
            runtime.stop();

            try {
                if (!runtime.awaitStopped(Duration.ofSeconds(20))) {
                    getLogger().severe("Omega Core cleanup exceeded its shutdown deadline.");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                getLogger().severe("Omega Core cleanup wait was interrupted.");
            }
        }
    }

    private RuntimeState state() {
        OmegaRuntime current = runtime;
        if (shutdownRequested.get()) {
            return RuntimeState.STOPPING;
        }

        return current == null ? RuntimeState.STARTING : current.state();
    }

    private void stopServer() {
        if (!shutdownRequested.compareAndSet(false, true)) {
            return;
        }

        getLogger().severe("Stopping the server: Omega Core is unavailable.");
        // Paper's shutdown API signals the server loop to halt; it does not mutate world state.
        getServer().shutdown();
    }
}
