package com.omegarealm.core.player.paper;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.omegarealm.core.RuntimeState;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Optional Floodgate integration. Each Bedrock join gets at most one bounded HTTP lookup;
 * the result is applied only to the exact current player on that entity's scheduler.
 */
public final class BedrockSkinAdapter implements Listener, AutoCloseable {

    private static final int MAX_LOOKUPS = 32;

    private final Object requests = new Object();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Plugin plugin;
    private final Supplier<RuntimeState> state;
    private final Function<UUID, String> bedrockXuid;
    private final ConvertedBedrockSkinLookup lookup;
    private final Logger logger;
    private boolean closed;

    public BedrockSkinAdapter(Plugin plugin, Supplier<RuntimeState> state, Logger logger) {
        this(plugin, state, floodgateXuidLookup(),
                new ConvertedBedrockSkinLookup(), logger);
    }

    BedrockSkinAdapter(Plugin plugin, Supplier<RuntimeState> state,
                       Function<UUID, String> bedrockXuid,
                       ConvertedBedrockSkinLookup lookup, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.state = Objects.requireNonNull(state, "state");
        this.bedrockXuid = Objects.requireNonNull(bedrockXuid, "bedrockXuid");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    private static Function<UUID, String> floodgateXuidLookup() {
        FloodgateApi floodgate = Objects.requireNonNull(FloodgateApi.getInstance(), "Floodgate API");
        return uuid -> {
            FloodgatePlayer player = floodgate.getPlayer(uuid);
            return player == null ? null : player.getXuid();
        };
    }

    private static void complete(CompletableFuture<RefreshResult> reply, RefreshResult outcome) {
        if (reply != null) {
            reply.complete(outcome);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        start(event.getPlayer(), false, null);
    }

    /**
     * Force a lookup and client re-registration on the target's own entity context.
     */
    public CompletableFuture<RefreshResult> refresh(Player player) {
        Objects.requireNonNull(player, "player");
        CompletableFuture<RefreshResult> reply = new CompletableFuture<>();
        try {
            boolean scheduled = player.getScheduler().execute(plugin,
                    () -> start(player, true, reply),
                    () -> reply.complete(RefreshResult.CANCELLED), 1);
            if (!scheduled) {
                reply.complete(RefreshResult.CANCELLED);
            }
        } catch (RuntimeException rejected) {
            reply.complete(RefreshResult.UNAVAILABLE);
        }
        return reply;
    }

    private void start(Player player, boolean force, CompletableFuture<RefreshResult> reply) {
        if (state.get() != RuntimeState.READY) {
            complete(reply, RefreshResult.UNAVAILABLE);
            return;
        }
        if (!player.isOnline()) {
            complete(reply, RefreshResult.CANCELLED);
            return;
        }
        String xuid;
        try {
            xuid = bedrockXuid.apply(player.getUniqueId());
        } catch (RuntimeException | LinkageError unavailable) {
            warnFailure();
            complete(reply, RefreshResult.FAILED);
            return;
        }
        if (xuid == null) {
            complete(reply, RefreshResult.NOT_BEDROCK);
            return;
        }
        if (!xuid.matches("[0-9]{1,20}")) {
            logger.warning("Floodgate did not supply a usable Bedrock XUID for skin lookup.");
            complete(reply, RefreshResult.FAILED);
            return;
        }

        Pending request = new Pending(player, player.getScheduler(), xuid, force, reply);
        Pending replaced = null;
        RefreshResult rejected = null;
        synchronized (requests) {
            if (closed) {
                rejected = RefreshResult.UNAVAILABLE;
            } else if (pending.size() >= MAX_LOOKUPS && !pending.containsKey(player.getUniqueId())) {
                rejected = RefreshResult.BUSY;
            } else {
                replaced = pending.put(player.getUniqueId(), request);
            }
        }
        if (rejected != null) {
            if (rejected == RefreshResult.BUSY) {
                logger.warning("Bedrock skin lookups are busy; leaving the current player skin in place.");
            }
            complete(reply, rejected);
            return;
        }
        if (replaced != null) {
            if (replaced.result != null) {
                replaced.result.cancel(true);
            }
            complete(replaced.reply, RefreshResult.CANCELLED);
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
            finish(request, RefreshResult.FAILED);
            warnFailure();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Pending request;
        synchronized (requests) {
            request = pending.get(event.getPlayer().getUniqueId());
            if (request != null && request.player == event.getPlayer()) {
                pending.remove(event.getPlayer().getUniqueId());
            } else {
                request = null;
            }
        }
        if (request != null && request.result != null) {
            request.result.cancel(true);
        }
        if (request != null) {
            complete(request.reply, RefreshResult.CANCELLED);
        }
    }

    private void complete(Pending request, Optional<ConvertedBedrockSkinLookup.SignedSkin> skin,
                          Throwable failure) {
        if (failure != null) {
            boolean cancelled = failure instanceof java.util.concurrent.CancellationException
                    || failure instanceof CompletionException completion
                    && completion.getCause() instanceof java.util.concurrent.CancellationException;
            finish(request, cancelled ? RefreshResult.CANCELLED : RefreshResult.FAILED);
            if (!cancelled) {
                warnFailure();
            }
            return;
        }
        if (skin.isEmpty()) {
            finish(request, RefreshResult.NO_CONVERTED_SKIN);
            return;
        }

        try {
            boolean scheduled = request.scheduler.execute(plugin,
                    () -> apply(request, skin.get()),
                    () -> finish(request, RefreshResult.CANCELLED), 1);
            if (!scheduled) {
                finish(request, RefreshResult.CANCELLED);
            }
        } catch (RuntimeException rejected) {
            finish(request, RefreshResult.UNAVAILABLE);
            if (state.get() == RuntimeState.READY) {
                logger.warning("Bedrock skin application could not be scheduled.");
            }
        }
    }

    private void apply(Pending request, ConvertedBedrockSkinLookup.SignedSkin skin) {
        RefreshResult outcome = RefreshResult.CANCELLED;
        try {
            Player player = request.player;
            synchronized (requests) {
                if (closed || pending.get(player.getUniqueId()) != request) {
                    return;
                }
            }
            if (state.get() != RuntimeState.READY || !player.isOnline()
                    || Bukkit.getPlayer(player.getUniqueId()) != player) {
                return;
            }
            if (!request.xuid.equals(bedrockXuid.apply(player.getUniqueId()))) {
                return;
            }

            PlayerProfile profile = player.getPlayerProfile();
            ProfileProperty current = profile.getProperties().stream()
                    .filter(property -> "textures".equals(property.getName()))
                    .findFirst().orElse(null);
            if (!request.force && current != null
                    && !ConvertedBedrockSkinLookup.isFloodgatePlaceholder(current.getValue())) {
                // Floodgate delivered a texture while HTTP was in flight. Keep the newer result.
                outcome = RefreshResult.ALREADY_CURRENT;
                return;
            }

            profile.setProperty(new ProfileProperty("textures", skin.value(), skin.signature()));
            player.setPlayerProfile(profile);
            logger.info("Recovered the converted Bedrock skin for " + player.getName() + ".");
            outcome = RefreshResult.APPLIED;
        } catch (RuntimeException | LinkageError failure) {
            outcome = RefreshResult.FAILED;
            warnFailure();
        } finally {
            finish(request, outcome);
        }
    }

    private void finish(Pending request, RefreshResult outcome) {
        boolean removed;
        synchronized (requests) {
            removed = pending.remove(request.player.getUniqueId(), request);
        }
        if (removed) {
            complete(request.reply, outcome);
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
        ArrayList<Pending> abandoned;
        synchronized (requests) {
            if (closed) {
                return;
            }
            closed = true;
            abandoned = new ArrayList<>(pending.values());
            for (Pending request : abandoned) {
                if (request.result != null) {
                    cancelled.add(request.result);
                }
            }
            pending.clear();
        }

        cancelled.forEach(result -> result.cancel(true));
        for (Pending request : abandoned) {
            complete(request.reply, RefreshResult.CANCELLED);
        }
        try {
            lookup.close();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            logger.warning("Bedrock skin HTTP cleanup was interrupted.");
        } catch (RuntimeException failure) {
            logger.warning("Bedrock skin HTTP cleanup did not finish normally.");
        }
    }

    public enum RefreshResult {
        APPLIED, ALREADY_CURRENT, NO_CONVERTED_SKIN, NOT_BEDROCK, BUSY, UNAVAILABLE, FAILED, CANCELLED
    }

    private static final class Pending {
        private final Player player;
        private final EntityScheduler scheduler;
        private final String xuid;
        private final boolean force;
        private final CompletableFuture<RefreshResult> reply;
        private CompletableFuture<Optional<ConvertedBedrockSkinLookup.SignedSkin>> result;

        private Pending(Player player, EntityScheduler scheduler, String xuid,
                        boolean force, CompletableFuture<RefreshResult> reply) {
            this.player = player;
            this.scheduler = scheduler;
            this.xuid = xuid;
            this.force = force;
            this.reply = reply;
        }
    }
}
