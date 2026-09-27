package com.omegarealm.core;

import com.omegarealm.core.authorization.paper.ConsoleRoleCommand;
import com.omegarealm.core.config.ConfigException;
import com.omegarealm.core.config.ConfigLoader;
import com.omegarealm.core.config.OmegaConfig;
import com.omegarealm.core.database.PostgresBootstrap;
import com.omegarealm.core.lifecycle.AdmissionGuard;
import com.omegarealm.core.player.paper.PlayerConnectionAdapter;
import com.omegarealm.core.scheduler.OmegaScheduler;
import com.omegarealm.core.scheduler.paper.PaperOmegaScheduler;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

public final class OmegaPlugin extends JavaPlugin {

    private final AtomicBoolean shutdownRequested = new AtomicBoolean();
    private volatile OmegaRuntime runtime;
    private PlayerConnectionAdapter playerConnections;
    private OmegaScheduler scheduler;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new AdmissionGuard(this::state), this);

        try {
            OmegaConfig config = new ConfigLoader().load(getDataFolder().toPath());
            PostgresBootstrap database = new PostgresBootstrap(config.database(), System::getenv,
                    getLogger(), getClass().getClassLoader(), config.debug());
            scheduler = new PaperOmegaScheduler(this, getServer().getGlobalRegionScheduler(),
                    getServer().getAsyncScheduler(), getLogger(), this::schedulingFailed);
            runtime = new OmegaRuntime(config, getLogger(), database,
                    scheduler::global, this::stopServer);
            playerConnections = new PlayerConnectionAdapter(this, this::state, runtime::players,
                    runtime::failPlayerHandling, getLogger(), Clock.systemUTC());
            getServer().getPluginManager().registerEvents(playerConnections, this);
            scheduler.repeatAsync(Duration.ofMillis(250), Duration.ofMillis(250), playerConnections::reconcile);
            ConsoleRoleCommand roleCommand = new ConsoleRoleCommand(getServer()::getConsoleSender,
                    this::state, runtime::players, scheduler, getLogger(), this::schedulingFailed);
            getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                    event -> event.registrar().register(roleCommand.node(), "Manage realm-wide player roles"));
            runtime.start();
        } catch (ConfigException exception) {
            getLogger().severe("Omega Core startup failed: " + exception.getMessage());
            stopServer();
        } catch (RuntimeException exception) {
            getLogger().severe("Unexpected Omega Core startup failure; check the deployment and configuration.");
            stopServer();
        }
    }

    @Override
    public void onDisable() {
        if (!getServer().isStopping()) {
            stopServer();
        }

        if (scheduler != null) {
            scheduler.close();
        }
        if (playerConnections != null) {
            playerConnections.close();
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
        if (shutdownRequested.get() || getServer().isStopping()) {
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

    private void schedulingFailed() {
        OmegaRuntime current = runtime;
        if (current == null) {
            stopServer();
        } else {
            current.failScheduling();
        }
    }
}
