package com.omegarealm.core.player.paper;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.omegarealm.core.RuntimeState;
import com.omegarealm.core.player.skin.BedrockSkinFingerprint;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Optional Floodgate integration. A join checks the signed conversion against the
 * current client skin, then briefly retries while Geyser publishes a newer conversion.
 * Application always runs on the exact current player's entity scheduler.
 */
public final class BedrockSkinAdapter implements Listener, PluginMessageListener, AutoCloseable {

    private static final int MAX_LOOKUPS = 32;
    private static final long[] RETRY_DELAYS_TICKS = {
            200, 400, 600, 900, 1200, 1800, 2400, 2400
    };

    private final Object requests = new Object();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Plugin plugin;
    private final Supplier<RuntimeState> state;
    private final Function<UUID, String> bedrockXuid;
    private final Function<UUID, Player> currentPlayer;
    private final ConvertedBedrockSkinLookup lookup;
    private final Logger logger;
    private boolean closed;

    public BedrockSkinAdapter(Plugin plugin, Supplier<RuntimeState> state, Logger logger) {
        this(plugin, state, floodgateXuidLookup(),
                new ConvertedBedrockSkinLookup(), Bukkit::getPlayer, logger);
    }

    BedrockSkinAdapter(Plugin plugin, Supplier<RuntimeState> state,
                       Function<UUID, String> bedrockXuid,
                       ConvertedBedrockSkinLookup lookup, Logger logger) {
        this(plugin, state, bedrockXuid, lookup, Bukkit::getPlayer, logger);
    }

    BedrockSkinAdapter(Plugin plugin, Supplier<RuntimeState> state,
                       Function<UUID, String> bedrockXuid,
                       ConvertedBedrockSkinLookup lookup,
                       Function<UUID, Player> currentPlayer, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.state = Objects.requireNonNull(state, "state");
        this.bedrockXuid = Objects.requireNonNull(bedrockXuid, "bedrockXuid");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.currentPlayer = Objects.requireNonNull(currentPlayer, "currentPlayer");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    private static Function<UUID, String> floodgateXuidLookup() {
        FloodgateApi floodgate = Objects.requireNonNull(FloodgateApi.getInstance(), "Floodgate API");
        return uuid -> {
            FloodgatePlayer player = floodgate.getPlayer(uuid);
            return player == null ? null : player.getXuid();
        };
    }

    private static void cancel(Retry retry) {
        if (retry != null && retry.task != null) {
            retry.task.cancel();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Session replaced;
        Pending abandoned;
        synchronized (requests) {
            if (closed) {
                return;
            }

            replaced = sessions.put(player.getUniqueId(), new Session(player));
            abandoned = pending.remove(player.getUniqueId());
        }
        if (replaced != null) {
            cancel(replaced.retry);
        }
        if (abandoned != null) {
            if (abandoned.result != null) {
                abandoned.result.cancel(true);
            }
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!BedrockSkinFingerprint.CHANNEL.equals(channel)) {
            return;
        }

        Optional<Set<String>> fingerprints = BedrockSkinFingerprint.read(message);
        if (fingerprints.isEmpty()) {
            return;
        }

        try {
            boolean scheduled = player.getScheduler().execute(plugin,
                    () -> receive(player, fingerprints.get()), () -> {
                    }, 1);
            if (!scheduled && state.get() == RuntimeState.READY) {
                logger.warning("Current Bedrock skin could not be scheduled for verification.");
            }
        } catch (RuntimeException rejected) {
            if (state.get() == RuntimeState.READY) {
                logger.warning("Current Bedrock skin could not be scheduled for verification.");
            }
        }
    }

    private void receive(Player player, Set<String> fingerprints) {
        synchronized (requests) {
            Session session = sessions.get(player.getUniqueId());
            if (closed || session == null || session.player != player) {
                return;
            }

            session.fingerprints = fingerprints;
        }

        start(player, 0);
    }

    private void start(Player player, int retryIndex) {
        if (state.get() != RuntimeState.READY) {
            return;
        }
        if (!player.isOnline()) {
            return;
        }
        String xuid;
        try {
            xuid = bedrockXuid.apply(player.getUniqueId());
        } catch (RuntimeException | LinkageError unavailable) {
            warnFailure();
            return;
        }
        if (xuid == null) {
            return;
        }
        if (!xuid.matches("[0-9]{1,20}")) {
            logger.warning("Floodgate did not supply a usable Bedrock XUID for skin lookup.");
            return;
        }

        UUID playerId = player.getUniqueId();
        EntityScheduler scheduler = player.getScheduler();
        Pending accepted = null;
        Pending replaced = null;
        Retry cancelledRetry = null;
        boolean busy = false;
        synchronized (requests) {
            Session session = sessions.get(playerId);
            Set<String> fingerprints = session != null && session.player == player
                    ? session.fingerprints : null;

            if (!closed && fingerprints != null
                    && pending.size() >= MAX_LOOKUPS && !pending.containsKey(playerId)) {
                busy = true;
            } else if (!closed && fingerprints != null) {
                session.revision++;
                cancelledRetry = session.retry;
                session.retry = null;
                accepted = new Pending(player, scheduler, xuid, fingerprints,
                        retryIndex, session.revision);
                replaced = pending.put(playerId, accepted);
            }
        }
        if (accepted == null) {
            if (busy) {
                logger.warning("Bedrock skin lookups are busy; leaving the current player skin in place.");
            }
            return;
        }
        Pending request = accepted;
        cancel(cancelledRetry);
        if (replaced != null) {
            if (replaced.result != null) {
                replaced.result.cancel(true);
            }
        }

        try {
            CompletableFuture<Optional<ConvertedBedrockSkinLookup.SignedSkin>> result = lookup.fetch(xuid);
            boolean cancelled;
            synchronized (requests) {
                cancelled = closed || pending.get(player.getUniqueId()) != request;
                if (!cancelled) {
                    request.result = result;
                }
            }
            if (cancelled) {
                result.cancel(true);
                return;
            }
            result.whenComplete((skin, failure) -> complete(request, skin, failure));
        } catch (RuntimeException failure) {
            finish(request);
            warnFailure();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        Pending request;
        Retry retry = null;
        synchronized (requests) {
            Session session = sessions.get(playerId);
            if (session != null && session.player == player) {
                sessions.remove(playerId);
                retry = session.retry;
            }

            request = pending.get(playerId);
            if (request != null && request.player == player) {
                pending.remove(playerId);
            } else {
                request = null;
            }
        }
        cancel(retry);
        if (request != null && request.result != null) {
            request.result.cancel(true);
        }
    }

    private void complete(Pending request, Optional<ConvertedBedrockSkinLookup.SignedSkin> skin,
                          Throwable failure) {
        if (failure != null) {
            boolean cancelled = failure instanceof CancellationException
                    || failure instanceof CompletionException completion
                    && completion.getCause() instanceof CancellationException;
            finish(request);
            if (!cancelled) {
                warnFailure();
            }
            return;
        }
        if (skin.isEmpty()) {
            if (finish(request)) {
                scheduleRetry(request);
            }
            return;
        }

        if (!request.fingerprints.contains(skin.get().hash())) {
            if (finish(request)) {
                scheduleRetry(request);
            }
            return;
        }

        try {
            boolean scheduled = request.scheduler.execute(plugin,
                    () -> apply(request, skin.get()),
                    () -> finish(request), 1);
            if (!scheduled) {
                finish(request);
            }
        } catch (RuntimeException rejected) {
            finish(request);
            if (state.get() == RuntimeState.READY) {
                logger.warning("Bedrock skin application could not be scheduled.");
            }
        }
    }

    private void apply(Pending request, ConvertedBedrockSkinLookup.SignedSkin skin) {
        try {
            Player player = request.player;
            synchronized (requests) {
                if (closed || pending.get(player.getUniqueId()) != request) {
                    return;
                }

                Session session = sessions.get(player.getUniqueId());
                if (session == null || session.player != player
                        || session.fingerprints != request.fingerprints) {
                    return;
                }
            }
            if (state.get() != RuntimeState.READY || !player.isOnline()
                    || currentPlayer.apply(player.getUniqueId()) != player) {
                return;
            }
            if (!request.xuid.equals(bedrockXuid.apply(player.getUniqueId()))) {
                return;
            }

            PlayerProfile profile = player.getPlayerProfile();
            ProfileProperty current = profile.getProperties().stream()
                    .filter(property -> "textures".equals(property.getName()))
                    .findFirst().orElse(null);
            if (current != null
                    && skin.value().equals(current.getValue())
                    && skin.signature().equals(current.getSignature())) {
                return;
            }

            profile.setProperty(new ProfileProperty("textures", skin.value(), skin.signature()));
            player.setPlayerProfile(profile);
            logger.info("Recovered the converted Bedrock skin for " + player.getName() + ".");
        } catch (RuntimeException | LinkageError failure) {
            warnFailure();
        } finally {
            finish(request);
        }
    }

    private boolean finish(Pending request) {
        synchronized (requests) {
            return pending.remove(request.player.getUniqueId(), request);
        }
    }

    private void scheduleRetry(Pending request) {
        if (request.retryIndex >= RETRY_DELAYS_TICKS.length) {
            return;
        }

        Retry retry = new Retry(request.player, request.fingerprints, request.revision,
                request.retryIndex + 1);
        synchronized (requests) {
            Session session = sessions.get(request.player.getUniqueId());
            if (closed || session == null || session.player != request.player
                    || session.fingerprints != request.fingerprints
                    || session.revision != request.revision || session.retry != null) {
                return;
            }
            session.retry = retry;
        }

        ScheduledTask task;
        try {
            task = request.scheduler.runDelayed(plugin,
                    ignored -> runRetry(retry),
                    () -> clearRetry(retry),
                    RETRY_DELAYS_TICKS[request.retryIndex]);
        } catch (RuntimeException rejected) {
            clearRetry(retry);
            return;
        }
        if (task == null) {
            clearRetry(retry);
            return;
        }

        boolean retained;
        synchronized (requests) {
            Session session = sessions.get(request.player.getUniqueId());
            retained = !closed && session != null && session.retry == retry;
            if (retained) {
                retry.task = task;
            }
        }
        if (!retained) {
            task.cancel();
        }
    }

    private void runRetry(Retry retry) {
        synchronized (requests) {
            Session session = sessions.get(retry.player.getUniqueId());
            if (closed || session == null || session.retry != retry
                    || session.player != retry.player
                    || session.fingerprints != retry.fingerprints
                    || session.revision != retry.revision) {
                return;
            }
            session.retry = null;
        }

        start(retry.player, retry.index);
    }

    private void clearRetry(Retry retry) {
        synchronized (requests) {
            Session session = sessions.get(retry.player.getUniqueId());
            if (session != null && session.retry == retry) {
                session.retry = null;
            }
        }
    }

    private void warnFailure() {
        synchronized (requests) {
            if (closed) {
                return;
            }
        }
        if (state.get() == RuntimeState.READY) {
            logger.warning("Converted Bedrock skin lookup failed; leaving the current player skin in place.");
        }
    }

    @Override
    public void close() {
        ArrayList<CompletableFuture<?>> cancelled = new ArrayList<>();
        ArrayList<Retry> retries = new ArrayList<>();
        synchronized (requests) {
            if (closed) {
                return;
            }
            closed = true;
            for (Pending request : pending.values()) {
                if (request.result != null) {
                    cancelled.add(request.result);
                }
            }
            pending.clear();
            for (Session session : sessions.values()) {
                if (session.retry != null) {
                    retries.add(session.retry);
                }
            }
            sessions.clear();
        }

        retries.forEach(BedrockSkinAdapter::cancel);
        cancelled.forEach(result -> result.cancel(true));
        try {
            lookup.close();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            logger.warning("Bedrock skin HTTP cleanup was interrupted.");
        } catch (RuntimeException failure) {
            logger.warning("Bedrock skin HTTP cleanup did not finish normally.");
        }
    }

    private static final class Session {
        private final Player player;
        private Set<String> fingerprints;
        private long revision;
        private Retry retry;

        private Session(Player player) {
            this.player = player;
        }
    }

    private static final class Pending {
        private final Player player;
        private final EntityScheduler scheduler;
        private final String xuid;
        private final Set<String> fingerprints;
        private final int retryIndex;
        private final long revision;
        private CompletableFuture<Optional<ConvertedBedrockSkinLookup.SignedSkin>> result;

        private Pending(Player player, EntityScheduler scheduler, String xuid,
                        Set<String> fingerprints, int retryIndex, long revision) {
            this.player = player;
            this.scheduler = scheduler;
            this.xuid = xuid;
            this.fingerprints = fingerprints;
            this.retryIndex = retryIndex;
            this.revision = revision;
        }
    }

    private static final class Retry {
        private final Player player;
        private final Set<String> fingerprints;
        private final long revision;
        private final int index;
        private ScheduledTask task;

        private Retry(Player player, Set<String> fingerprints, long revision, int index) {
            this.player = player;
            this.fingerprints = fingerprints;
            this.revision = revision;
            this.index = index;
        }
    }
}
