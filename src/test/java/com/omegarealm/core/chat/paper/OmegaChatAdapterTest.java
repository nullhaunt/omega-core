package com.omegarealm.core.chat.paper;

import com.omegarealm.core.RuntimeState;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OmegaChatAdapterTest {

    private static AsyncChatEvent event(Set<Audience> viewers) {
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getName")) {
                        return "Alex";
                    }
                    return objectMethod(proxy, method, arguments);
                });
        Component original = Component.text("raw text");
        SignedMessage signed = SignedMessage.system("raw text", original);
        return new AsyncChatEvent(true, player, viewers,
                (source, name, message, viewer) -> message, original, original, signed);
    }

    private static Audience audience(List<String> messages) {
        return (Audience) Proxy.newProxyInstance(Audience.class.getClassLoader(),
                new Class<?>[]{Audience.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("sendMessage")) {
                        if (arguments.length != 1 || !(arguments[0] instanceof Component component)) {
                            throw new AssertionError("Chat must use component system-message delivery");
                        }
                        messages.add(PlainTextComponentSerializer.plainText().serialize(component));
                        return null;
                    }
                    return objectMethod(proxy, method, arguments);
                });
    }

    private static Object objectMethod(Object proxy, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            case "toString" -> "test audience";
            default -> throw new AssertionError(method.getName());
        };
    }

    @Test
    void cancelsPaperDeliveryAndSendsModifiedContentOnlyToRetainedViewers() {
        List<String> javaMessages = new ArrayList<>();
        List<String> bedrockMessages = new ArrayList<>();
        List<String> excludedMessages = new ArrayList<>();
        Audience javaViewer = audience(javaMessages);
        Audience bedrockViewer = audience(bedrockMessages);
        Audience excludedViewer = audience(excludedMessages);
        Set<Audience> viewers = new LinkedHashSet<>(List.of(javaViewer, bedrockViewer, excludedViewer));
        viewers.remove(excludedViewer);
        AsyncChatEvent event = event(viewers);
        event.message(Component.text("approved text"));
        AtomicInteger failures = new AtomicInteger();

        new OmegaChatAdapter(() -> RuntimeState.READY, failures::incrementAndGet).onChat(event);

        assertTrue(event.isCancelled());
        assertEquals(List.of("Alex » approved text"), javaMessages);
        assertEquals(javaMessages, bedrockMessages);
        assertTrue(excludedMessages.isEmpty());
        assertEquals(0, failures.get());
    }

    @Test
    void respectsEarlierCancellationAndDropsChatOutsideReady() {
        List<String> messages = new ArrayList<>();
        AsyncChatEvent cancelled = event(Set.of(audience(messages)));
        cancelled.setCancelled(true);
        new OmegaChatAdapter(() -> RuntimeState.READY, () -> fail("Unexpected failure"))
                .onChat(cancelled);
        assertTrue(messages.isEmpty());

        AsyncChatEvent starting = event(Set.of(audience(messages)));
        new OmegaChatAdapter(() -> RuntimeState.STARTING, () -> fail("Unexpected failure"))
                .onChat(starting);
        assertTrue(starting.isCancelled());
        assertTrue(messages.isEmpty());
    }

    @Test
    void deliveryFailureLeavesSignedEventCancelledAndFailsRuntime() {
        Audience broken = (Audience) Proxy.newProxyInstance(Audience.class.getClassLoader(),
                new Class<?>[]{Audience.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("sendMessage")) {
                        throw new IllegalStateException("viewer unavailable");
                    }
                    return objectMethod(proxy, method, arguments);
                });
        AsyncChatEvent event = event(Set.of(broken));
        AtomicInteger failures = new AtomicInteger();

        new OmegaChatAdapter(() -> RuntimeState.READY, failures::incrementAndGet).onChat(event);

        assertTrue(event.isCancelled());
        assertEquals(1, failures.get());
    }
}
