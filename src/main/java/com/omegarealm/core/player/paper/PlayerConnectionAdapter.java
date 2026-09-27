package com.omegarealm.core.player.paper;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.omegarealm.core.RuntimeState;
import com.omegarealm.core.authorization.AuthorizationSnapshot;
import com.omegarealm.core.player.*;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.net.SocketAddress;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Paper owns event threads and the reconciliation task. This adapter owns connection
 * handles; the application service owns immutable attempts and all persistence work.
 * Only the asynchronous configuration handler waits, never a tick or entity handler.
 */
public final class PlayerConnectionAdapter implements Listener, AutoCloseable {

    private static final Component UNAVAILABLE = Component.text("Omega Realm is temporarily unavailable. Please try again later.");
    private static final Component RETRY = Component.text("Your player data could not finish loading. Please reconnect.");
    private static final Component DUPLICATE = Component.text("Your previous connection is still active. Please try again shortly.");
    private static final Component BUSY = Component.text("Omega Realm is busy loading players. Please try again shortly.");
    private static final Component AUTHORITY_EXPIRED = Component.text("Your permissions could not be refreshed. Please reconnect.");
    private static final long RETENTION_NANOS = PlayerServiceLimits.DEFAULT.loadTimeout()
            .plus(PlayerServiceLimits.DEFAULT.preparedTimeout()).toNanos();

    private final Object connections = new Object();
    private final Map<SocketAddress, Attempt> pending = new HashMap<>();
    private final Map<Player, Attempt> joined = new IdentityHashMap<>();
    private final Supplier<RuntimeState> state;
    private final Supplier<PlayerService> players;
    private final Runnable criticalFailure;
    private final Logger logger;
    private final Clock time;
    private final Plugin plugin;
    private boolean closed;

    public PlayerConnectionAdapter(Plugin plugin, Supplier<RuntimeState> state, Supplier<PlayerService> players,
                                   Runnable criticalFailure, Logger logger, Clock time) {
        this.state = Objects.requireNonNull(state, "state");
        this.players = Objects.requireNonNull(players, "players");
        this.criticalFailure = Objects.requireNonNull(criticalFailure, "criticalFailure");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.time = Objects.requireNonNull(time, "time");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    private static PlayerIdentity identity(PlayerProfile profile) {
        return profile == null || profile.getId() == null || profile.getName() == null
                ? null : new PlayerIdentity(profile.getId(), profile.getName());
    }

    /**
     * Identity lookup only: UUID equality must never authenticate a replacement connection.
     */
    public Optional<AuthorizationSnapshot> authorization(Player player) {
        Attempt attempt;
        synchronized (connections) {
            attempt = closed ? null : joined.get(player);
        }
        return attempt == null || state.get() != RuntimeState.READY
                ? Optional.empty() : attempt.service.authorization(attempt.attempt.token());
    }

    /**
     * Schedule a reply using the handle captured on this exact connection's join thread.
     */
    public void reply(Player player, Runnable reply) {
        Attempt attempt;
        synchronized (connections) {
            attempt = closed ? null : joined.get(player);
        }
        if (attempt == null) {
            return;
        }
        attempt.entityScheduler.execute(plugin, () -> {
            try {
                if (authorization(player).isPresent()) {
                    reply.run();
                }
            } catch (RuntimeException failure) {
                failAdapter();
            }
        }, () -> {
        }, 1);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
        if (!event.isAsynchronous()) {
            failAdapter();
            return;
        }

        Attempt attempt = null;
        try {
            // A rapid reconnect can reuse a raw endpoint before the periodic sweep runs.
            reconcile();
            PlayerConfigurationConnection connection = event.getConnection();
            PlayerService service = availableService();
            if (service == null || !connection.isConnected()) {
                return;
            }
            PlayerIdentity identity = identity(connection.getProfile());
            SocketAddress address = connection.getAddress();
            if (identity == null || address == null) {
                return;
            }
            synchronized (connections) {
                if (closed) {
                    return;
                }
                Attempt existing = pending.get(address);
                if (existing != null) {
                    // Repeated configuration does not allocate another token. Validation
                    // requires the exact pending handle; an active reconfiguration keeps
                    // its existing play session.
                    if (existing.connection != connection || existing.active || existing.rejection != null) {
                        return;
                    }
                    attempt = existing;
                } else {
                    if (pending.size() >= PlayerServiceLimits.DEFAULT.trackedAttempts()) {
                        return;
                    }
                    attempt = new Attempt(address, connection, new PlayerAttempt(UUID.randomUUID(), identity), service);
                    pending.put(address, attempt);
                }
            }

            CompletableFuture<PreparedPlayer> loading = service.prepare(attempt.attempt).toCompletableFuture();
            if (!isCurrent(attempt)) {
                cancel(attempt);
                return;
            }
            awaitPreparation(attempt, loading);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (attempt != null) {
                reject(attempt, RETRY);
            }
        } catch (RuntimeException failure) {
            if (attempt != null) {
                reject(attempt, UNAVAILABLE);
            }
            failAdapter();
        }
    }

    private void awaitPreparation(Attempt attempt, CompletableFuture<PreparedPlayer> loading)
            throws InterruptedException {
        long deadline = System.nanoTime() + PlayerServiceLimits.DEFAULT.loadTimeout().toNanos();
        while (isCurrent(attempt) && state.get() == RuntimeState.READY && attempt.connection.isConnected()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                reject(attempt, RETRY);
                return;
            }
            try {
                loading.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
                return;
            } catch (TimeoutException stillLoading) {
                // Poll the exact connection so a disconnect releases the attempt promptly.
            } catch (ExecutionException failure) {
                Component message = UNAVAILABLE;
                if (failure.getCause() instanceof PlayerOperationException operation) {
                    message = switch (operation.kind()) {
                        case BUSY -> BUSY;
                        case DUPLICATE -> DUPLICATE;
                        case TIMED_OUT, CANCELLED, NOT_PREPARED -> RETRY;
                        case UNAVAILABLE -> UNAVAILABLE;
                    };
                }
                reject(attempt, message);
                return;
            }
        }
        remove(attempt);
        cancel(attempt);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onValidate(PlayerConnectionValidateLoginEvent event) {
        // Initial LOGIN validation precedes configuration. The existing guard checks
        // readiness there; only configuration completion requires a prepared attempt.
        if (event.getConnection() instanceof PlayerLoginConnection) {
            return;
        }
        if (!(event.getConnection() instanceof PlayerConfigurationConnection connection)) {
            if (event.isAllowed()) {
                event.kickMessage(UNAVAILABLE);
            }
            failAdapter();
            return;
        }
        try {
            Attempt attempt = lookup(connection.getAddress(), identity(connection.getProfile()));
            if (attempt != null && !attempt.active && attempt.connection != connection) {
                attempt = null;
            }
            if (!event.isAllowed()) {
                if (attempt != null && !attempt.active) {
                    remove(attempt);
                    cancel(attempt);
                }
                return;
            }
            if (state.get() != RuntimeState.READY || !connection.isConnected() || attempt == null
                    || !attempt.connection.isConnected()) {
                event.kickMessage(UNAVAILABLE);
            } else if (!attempt.active && !attempt.service.isPrepared(attempt.attempt.token())) {
                event.kickMessage(attempt.rejection == null ? RETRY : attempt.rejection);
            }
            if (!event.isAllowed() && attempt != null && !attempt.active) {
                remove(attempt);
                cancel(attempt);
            }
        } catch (RuntimeException failure) {
            if (event.isAllowed()) {
                event.kickMessage(UNAVAILABLE);
            }
            failAdapter();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        try {
            Player player = event.getPlayer();
            EntityScheduler entityScheduler = player.getScheduler();
            Attempt attempt = lookup(player.getConnection().getAddress(),
                    new PlayerIdentity(player.getUniqueId(), player.getName()));
            boolean invalid;
            synchronized (connections) {
                if (joined.containsKey(player)) {
                    return;
                }
                invalid = closed || attempt == null || attempt.active;
                if (!invalid) {
                    attempt.active = true;
                    attempt.player = player;
                    attempt.entityScheduler = entityScheduler;
                    joined.put(player, attempt);
                }
            }
            if (invalid) {
                failAdapter();
                return;
            }
            attempt.service.activate(attempt.attempt.token(), time.instant()).whenComplete((profile, failure) -> {
                Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
                if (failure != null && state.get() == RuntimeState.READY
                        && !(cause instanceof PlayerOperationException operation
                        && operation.kind() == PlayerOperationException.Kind.CANCELLED)) {
                    failAdapter();
                }
            });
        } catch (RuntimeException failure) {
            failAdapter();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Attempt attempt;
        synchronized (connections) {
            attempt = joined.remove(event.getPlayer());
            if (attempt != null) {
                pending.remove(attempt.address, attempt);
            }
        }
        if (attempt != null && state.get() == RuntimeState.READY) {
            cancel(attempt);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConnectionClose(PlayerConnectionCloseEvent event) {
        // This event has no connection token. Sweep exact handles, never remove by UUID.
        reconcile();
    }

    /**
     * Called by one plugin-owned Paper async task, including when no close event arrives.
     */
    public void reconcile() {
        ArrayList<Attempt> snapshot;
        synchronized (connections) {
            snapshot = new ArrayList<>(pending.values());
        }
        try {
            for (Attempt attempt : snapshot) {
                if (attempt.active && attempt.service.requiresAuthorizationReconnect(attempt.attempt.token())) {
                    reconnectExpired(attempt);
                }
                if (!attempt.active && (!attempt.connection.isConnected()
                        || System.nanoTime() - attempt.created >= RETENTION_NANOS
                        || state.get() != RuntimeState.READY)) {
                    if (remove(attempt)) {
                        cancel(attempt);
                    }
                }
            }
        } catch (RuntimeException failure) {
            failAdapter();
        }
    }

    private void reconnectExpired(Attempt attempt) {
        synchronized (connections) {
            if (closed || attempt.reconnectScheduled || joined.get(attempt.player) != attempt) {
                return;
            }
            attempt.reconnectScheduled = true;
        }
        // The handle was captured at join on the player thread. Retired entities need no
        // kick; quit/shutdown owns session closure. A reconnect gets a different token.
        attempt.entityScheduler.execute(plugin, () -> {
            try {
                synchronized (connections) {
                    if (closed || joined.get(attempt.player) != attempt) {
                        return;
                    }
                }
                if (state.get() == RuntimeState.READY
                        && attempt.service.requiresAuthorizationReconnect(attempt.attempt.token())) {
                    attempt.player.kick(AUTHORITY_EXPIRED);
                }
            } catch (RuntimeException failure) {
                failAdapter();
            }
        }, () -> {
        }, 1);
    }

    @Override
    public void close() {
        synchronized (connections) {
            closed = true;
            pending.clear();
            joined.clear();
        }
        // Runtime stop owns SHUTDOWN timestamps and the persistence drain.
    }

    private PlayerService availableService() {
        if (state.get() != RuntimeState.READY) {
            return null;
        }
        try {
            return players.get();
        } catch (IllegalStateException racedStop) {
            return null;
        }
    }

    private Attempt lookup(SocketAddress address, PlayerIdentity identity) {
        synchronized (connections) {
            // Configuration and game use different Paper wrappers. Build 42 exposes
            // the same raw channel endpoint in both; UUID/name must also match. Pending
            // validation additionally checks the exact configuration wrapper above.
            Attempt attempt = pending.get(address);
            return !closed && attempt != null && attempt.attempt.identity().equals(identity) ? attempt : null;
        }
    }

    private boolean isCurrent(Attempt attempt) {
        synchronized (connections) {
            return !closed && pending.get(attempt.address) == attempt;
        }
    }

    private boolean remove(Attempt attempt) {
        synchronized (connections) {
            return !attempt.active && pending.remove(attempt.address, attempt);
        }
    }

    private void reject(Attempt attempt, Component message) {
        attempt.rejection = message;
        cancel(attempt);
    }

    private void cancel(Attempt attempt) {
        try {
            attempt.service.disconnect(attempt.attempt.token(), time.instant());
        } catch (RuntimeException failure) {
            failAdapter();
        }
    }

    private void failAdapter() {
        logger.severe("Player connection handling failed; requesting server shutdown.");
        criticalFailure.run();
    }

    private static final class Attempt {
        private final SocketAddress address;
        private final PlayerConfigurationConnection connection;
        private final PlayerAttempt attempt;
        private final PlayerService service;
        private final long created = System.nanoTime();
        private volatile boolean active;
        private volatile Component rejection;
        private Player player;
        private EntityScheduler entityScheduler;
        private boolean reconnectScheduled;

        private Attempt(SocketAddress address, PlayerConfigurationConnection connection,
                        PlayerAttempt attempt, PlayerService service) {
            this.address = address;
            this.connection = connection;
            this.attempt = attempt;
            this.service = service;
        }
    }
}
