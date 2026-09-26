package com.omegarealm.core;

import com.omegarealm.core.config.ConfigException;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class OmegaPlugin extends JavaPlugin {

    private OmegaRuntime runtime;
    private boolean shutdownRequested;

    @Override
    public void onEnable() {
        try {
            runtime = OmegaRuntime.start(this);
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
            runtime = null;
        }
    }

    private void stopServer() {
        if (shutdownRequested) {
            return;
        }

        shutdownRequested = true;
        getLogger().severe("Stopping the server: Omega Core is unavailable.");
        getServer().shutdown();
    }
}
