package com.omegarealm.core;

import com.omegarealm.core.authorization.paper.NativeCommandFence;
import com.omegarealm.core.authorization.paper.PlayerPermissionAdapter;
import com.omegarealm.core.chat.paper.OmegaChatAdapter;
import com.omegarealm.core.command.paper.CommandAccess;
import com.omegarealm.core.command.paper.CommandAudit;
import com.omegarealm.core.command.paper.OmegaCommands;
import com.omegarealm.core.config.ConfigException;
import com.omegarealm.core.config.ConfigLoader;
import com.omegarealm.core.config.OmegaConfig;
import com.omegarealm.core.database.PostgresBootstrap;
import com.omegarealm.core.diagnostics.BuildIdentity;
import com.omegarealm.core.lifecycle.AdmissionGuard;
import com.omegarealm.core.player.paper.BedrockSkinAdapter;
import com.omegarealm.core.player.paper.PlayerConnectionAdapter;
import com.omegarealm.core.scheduler.OmegaScheduler;
import com.omegarealm.core.scheduler.paper.PaperOmegaScheduler;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

public final class OmegaPlugin extends JavaPlugin implements Listener {

    private final AtomicBoolean shutdownRequested = new AtomicBoolean();
    private volatile OmegaRuntime runtime;
    private PlayerConnectionAdapter playerConnections;
    private OmegaScheduler scheduler;
    private PlayerPermissionAdapter playerPermissions;
    private NativeCommandFence nativeCommands;
    private BedrockSkinAdapter bedrockSkins;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new AdmissionGuard(this::state), this);
        getServer().getPluginManager().registerEvents(this, this);

        try {
            OmegaConfig config = new ConfigLoader().load(getDataFolder().toPath());
            PostgresBootstrap database = new PostgresBootstrap(config.database(), System::getenv,
                    getLogger(), getClass().getClassLoader(), config.debug());
            scheduler = new PaperOmegaScheduler(this, getServer().getGlobalRegionScheduler(),
                    getServer().getAsyncScheduler(), getLogger(), this::schedulingFailed);
            runtime = new OmegaRuntime(config, getLogger(), database,
                    scheduler::global, this::stopServer);
            playerConnections = new PlayerConnectionAdapter(this, this::state, runtime::players,
                    runtime::failPlayerHandling, getLogger(), Clock.systemUTC(), config.debug());
            getServer().getPluginManager().registerEvents(playerConnections, this);
            getServer().getPluginManager().registerEvents(
                    new OmegaChatAdapter(this::state, runtime::failChatHandling), this);
            playerPermissions = new PlayerPermissionAdapter(this, playerConnections::authorization, runtime::failPlayerHandling);
            CommandAccess commandAccess = new CommandAccess(getServer()::getConsoleSender,
                    this::state, playerPermissions::authorization);
            CommandAudit commandAudit = new CommandAudit(getLogger(), config.server().id(), runtime::players);
            BuildIdentity build = BuildIdentity.load(getClass().getClassLoader());
            OmegaCommands commands = new OmegaCommands(commandAccess, runtime::players,
                    build, runtime::diagnostics,
                    scheduler, commandAudit, getLogger(), this::schedulingFailed,
                    playerConnections::reply, () -> bedrockSkins);
            nativeCommands = new NativeCommandFence(this, playerPermissions, commandAccess,
                    commandAudit, commands, runtime::failPlayerHandling,
                    () -> state() == RuntimeState.READY, runtime::start);
            getServer().getPluginManager().registerEvents(playerPermissions, this);
            getServer().getPluginManager().registerEvents(nativeCommands, this);
            scheduler.repeatAsync(Duration.ofMillis(250), Duration.ofMillis(250), () -> {
                playerConnections.reconcile();
                playerPermissions.reconcile();
            });
            scheduler.repeatAsync(Duration.ofSeconds(1), Duration.ofSeconds(1),
                    () -> scheduler.global(playerPermissions::refreshPlan));
            getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
                try {
                    commands.register(event.registrar());
                } catch (RuntimeException | LinkageError unavailable) {
                    getLogger().severe("Omega command registration failed; stopping the server.");
                    stopServer();
                }
            });
            // ServerLoad installs the native permission boundary before starting critical services.
        } catch (ConfigException exception) {
            getLogger().severe("Omega Core startup failed: " + exception.getMessage());
            stopServer();
        } catch (RuntimeException | LinkageError exception) {
            getLogger().severe("Unexpected Omega Core startup failure; check the deployment and configuration.");
            stopServer();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event) {
        if (event.getType() != ServerLoadEvent.LoadType.STARTUP || shutdownRequested.get()) {
            return;
        }

        Plugin floodgate = getServer().getPluginManager().getPlugin("floodgate");
        if (floodgate == null) {
            return;
        }
        if (!floodgate.isEnabled()) {
            getLogger().warning("Floodgate is installed but unavailable; Bedrock skin recovery is disabled.");
            return;
        }

        BedrockSkinAdapter started = null;
        try {
            started = new BedrockSkinAdapter(this, this::state, getLogger());
            getServer().getPluginManager().registerEvents(started, this);
            bedrockSkins = started;
            getLogger().info("Bedrock skin recovery is ready.");
        } catch (RuntimeException | LinkageError unavailable) {
            if (started != null) {
                started.close();
            }
            getLogger().warning("Bedrock skin recovery could not start; player admission remains available.");
        }
    }

    @Override
    public void onDisable() {
        if (!getServer().isStopping()) {
            stopServer();
        }

        if (bedrockSkins != null) {
            bedrockSkins.close();
        }
        if (scheduler != null) {
            scheduler.close();
        }
        if (playerConnections != null) {
            playerConnections.close();
        }
        if (playerPermissions != null) {
            playerPermissions.close();
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

        RuntimeState state = current == null ? RuntimeState.STARTING : current.state();
        return state == RuntimeState.READY && (nativeCommands == null || !nativeCommands.installed())
                ? RuntimeState.STARTING : state;
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
