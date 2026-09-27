package com.omegarealm.core.authorization.paper;

import com.omegarealm.core.authorization.AuthorizationSnapshot;
import com.omegarealm.core.authorization.NativeCommandCatalog;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Owns one attachment per exact player object; all player mutation runs on its entity scheduler.
 */
public final class PlayerPermissionAdapter implements Listener, AutoCloseable {

    private static final long APPLY_TIMEOUT = java.time.Duration.ofSeconds(5).toNanos();
    private final Plugin plugin;
    private final Function<Player, Optional<AuthorizationSnapshot>> authority;
    private final LongSupplier nanoTime;
    private final Runnable failure;
    private final Map<Player, Handle> handles = Collections.synchronizedMap(new IdentityHashMap<>());
    private volatile List<String> plan = List.of();
    private volatile boolean closed;

    public PlayerPermissionAdapter(Plugin plugin, Function<Player, Optional<AuthorizationSnapshot>> authority,
                                   Runnable failure) {
        this(plugin, authority, failure, System::nanoTime);
    }

    PlayerPermissionAdapter(Plugin plugin, Function<Player, Optional<AuthorizationSnapshot>> authority,
                            Runnable failure, LongSupplier nanoTime) {
        this.plugin = plugin;
        this.authority = authority;
        this.failure = failure;
        this.nanoTime = nanoTime;
    }

    public Optional<AuthorizationSnapshot> authorization(Player player) {
        Handle handle = handles.get(player);
        Optional<AuthorizationSnapshot> current = authority.apply(player);
        return handle == null || closed || handle.expired || !current.equals(handle.applied) || !plan.equals(handle.plan)
                ? Optional.empty() : current;
    }

    /**
     * Global context: permission registration belongs to server/plugin infrastructure.
     */
    public void refreshPlan() {
        plan = PermissionPlan.order(plugin.getServer().getPluginManager().getPermissions());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        try {
            Player player = event.getPlayer();
            Handle handle = new Handle(player, player.getScheduler(), player.addAttachment(plugin));
            handles.put(player, handle);
            apply(handle);
        } catch (RuntimeException unavailable) {
            failure.run();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Handle handle = handles.remove(event.getPlayer());
        if (handle != null) {
            try {
                handle.attachment.remove();
            } catch (RuntimeException unavailable) {
                failure.run();
            }
        }
    }

    /**
     * Existing async reconciliation owns observation; never read player state on this thread.
     */
    public void reconcile() {
        List<Handle> snapshot;
        synchronized (handles) {
            snapshot = List.copyOf(handles.values());
        }
        for (Handle handle : snapshot) {
            Optional<AuthorizationSnapshot> current = authority.apply(handle.player);
            long observedAt = nanoTime.getAsLong();
            synchronized (handle) {
                if (!current.equals(handle.applied) || !plan.equals(handle.plan)) {
                    if (handle.pendingSince == 0) {
                        handle.pendingSince = observedAt;
                    } else if (observedAt - handle.pendingSince >= APPLY_TIMEOUT) {
                        // A terminal flag denies commands even while the owning thread is delayed.
                        handle.expired = true;
                    }
                } else {
                    handle.pendingSince = 0;
                }
            }
            if (!closed && handle.queued.compareAndSet(false, true)) {
                try {
                    boolean accepted = handle.scheduler.execute(plugin, () -> {
                        try {
                            if (!closed && handles.get(handle.player) == handle) {
                                apply(handle);
                            }
                        } catch (RuntimeException unavailable) {
                            failure.run();
                        } finally {
                            handle.queued.set(false);
                        }
                    }, () -> handle.queued.set(false), 1);
                    if (!accepted) {
                        handle.queued.set(false);
                    }
                } catch (RuntimeException unavailable) {
                    handle.queued.set(false);
                    failure.run();
                }
            }
        }
    }

    private void apply(Handle handle) {
        if (handle.player.isOp()) {
            handle.player.setOp(false);
        }
        Optional<AuthorizationSnapshot> current = handle.expired ? Optional.empty()
                : authority.apply(handle.player);
        List<String> currentPlan = plan;
        if (!current.equals(handle.applied) || !currentPlan.equals(handle.plan)) {
            Set<String> grants = current.map(snapshot -> NativeCommandCatalog.permissions(snapshot.role()))
                    .orElse(Set.of());
            for (String permission : currentPlan) {
                handle.attachment.setPermission(permission, grants.contains(permission));
            }
            synchronized (handle) {
                handle.plan = currentPlan;
                handle.applied = current;
                handle.pendingSince = 0;
            }
            handle.player.updateCommands();
        }
        if (handle.expired) {
            handle.player.kick(Component.text("Your permissions could not be applied. Please reconnect."));
        }
    }

    @Override
    public void close() {
        closed = true;
        handles.clear();
        // Paper removes plugin attachments at disable; no off-thread entity access during stop.
    }

    private static final class Handle {
        private final Player player;
        private final EntityScheduler scheduler;
        private final PermissionAttachment attachment;
        private final AtomicBoolean queued = new AtomicBoolean();
        private volatile Optional<AuthorizationSnapshot> applied = Optional.empty();
        private volatile List<String> plan = List.of();
        private long pendingSince;
        private volatile boolean expired;

        private Handle(Player player, EntityScheduler scheduler, PermissionAttachment attachment) {
            this.player = player;
            this.scheduler = scheduler;
            this.attachment = attachment;
        }
    }
}
