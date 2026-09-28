package com.omegarealm.core.chat.paper;

import com.omegarealm.core.RuntimeState;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Replaces Paper's signed public-chat delivery with server-authored messages.
 * The incoming signature is never forwarded to a viewer.
 */
public final class OmegaChatAdapter implements Listener {

    private final Supplier<RuntimeState> state;
    private final Runnable criticalFailure;

    public OmegaChatAdapter(Supplier<RuntimeState> state, Runnable criticalFailure) {
        this.state = Objects.requireNonNull(state, "state");
        this.criticalFailure = Objects.requireNonNull(criticalFailure, "criticalFailure");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (event.isCancelled()) {
            return;
        }

        // Cancel first so a failure cannot fall back to the signed broadcast.
        event.setCancelled(true);
        try {
            if (state.get() != RuntimeState.READY) {
                return;
            }

            String text = PlainTextComponentSerializer.plainText().serialize(event.message());
            Component message = Component.text(event.getPlayer().getName(), NamedTextColor.AQUA)
                    .append(Component.text(" » ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(text, NamedTextColor.WHITE));

            // Paper's chat audience includes only the viewers retained by earlier listeners.
            // Component delivery is a system message, including for Java players.
            for (Audience viewer : event.viewers()) {
                viewer.sendMessage(message);
            }
        } catch (RuntimeException | LinkageError failure) {
            criticalFailure.run();
        }
    }
}
