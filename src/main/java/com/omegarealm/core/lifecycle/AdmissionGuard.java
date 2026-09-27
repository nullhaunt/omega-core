package com.omegarealm.core.lifecycle;

import com.omegarealm.core.RuntimeState;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.function.Supplier;

/**
 * Fail-closed admission policy. It reads state only and never performs I/O.
 */
public final class AdmissionGuard implements Listener {

    private static final Component STARTING_MESSAGE = Component.text("Omega Realm is starting. Please try again shortly.");
    private static final Component UNAVAILABLE_MESSAGE = Component.text("Omega Realm is temporarily unavailable. Please try again later.");

    private final Supplier<RuntimeState> state;

    public AdmissionGuard(Supplier<RuntimeState> state) {
        this.state = state;
    }

    private static Component message(RuntimeState state) {
        return state == RuntimeState.STARTING ? STARTING_MESSAGE : UNAVAILABLE_MESSAGE;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        RuntimeState current = state.get();
        if (current != RuntimeState.READY && event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, message(current));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onValidateLogin(PlayerConnectionValidateLoginEvent event) {
        RuntimeState current = state.get();
        if (current != RuntimeState.READY && event.isAllowed()) {
            event.kickMessage(message(current));
        }
    }
}
