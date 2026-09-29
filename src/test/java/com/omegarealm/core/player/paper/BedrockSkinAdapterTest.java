package com.omegarealm.core.player.paper;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.omegarealm.core.RuntimeState;
import com.omegarealm.core.player.skin.BedrockSkinFingerprint;
import com.sun.net.httpserver.HttpServer;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class BedrockSkinAdapterTest {

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, arguments) -> invocation.call(method.getName(), arguments)));
    }

    @Test
    void joinWaitsForTheClientSkinAndRejectsAnOlderConversion()
            throws Exception {
        String texture = "{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/"
                + "b833f42ff2ffa01c8a65f9c7099062493e0b519ea9194db33f105e6092c8a55b\"}}}";
        String value = Base64.getEncoder().encodeToString(texture.getBytes(StandardCharsets.UTF_8));
        byte[] body = ("{\"hash\":\"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff\",\"value\":\"" + value
                + "\",\"signature\":\"signed\"}").getBytes(StandardCharsets.UTF_8);
        AtomicInteger lookups = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/skin/123", exchange -> {
            lookups.incrementAndGet();
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

        UUID uuid = UUID.randomUUID();
        CountDownLatch retryScheduled = new CountDownLatch(1);
        EntityScheduler scheduler = proxy(EntityScheduler.class, (method, arguments) -> {
            if (method.equals("execute")) {
                ((Runnable) arguments[1]).run();
                return true;
            }
            if (method.equals("runDelayed")) {
                retryScheduled.countDown();
                return null;
            }
            throw new AssertionError(method);
        });
        Player player = proxy(Player.class, (method, arguments) -> switch (method) {
            case "getScheduler" -> scheduler;
            case "isOnline" -> true;
            case "getUniqueId" -> uuid;
            default -> throw new AssertionError("A stale conversion touched " + method);
        });
        Plugin plugin = proxy(Plugin.class, (method, arguments) -> null);
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/skin/");
        ConvertedBedrockSkinLookup lookup =
                new ConvertedBedrockSkinLookup(HttpClient.newHttpClient(), endpoint);
        BedrockSkinAdapter adapter = new BedrockSkinAdapter(plugin, () -> RuntimeState.READY,
                ignored -> "123", lookup, Logger.getAnonymousLogger());

        try {
            adapter.onJoin(new PlayerJoinEvent(player, Component.empty()));
            assertEquals(0, lookups.get());

            byte[] fingerprint = BedrockSkinFingerprint.forClassicSkin(new byte[64 * 64 * 4],
                    64, 64).orElseThrow();
            adapter.onPluginMessageReceived(BedrockSkinFingerprint.CHANNEL, player, fingerprint);
            assertTrue(retryScheduled.await(3, TimeUnit.SECONDS));
            assertEquals(1, lookups.get());
        } finally {
            adapter.close();
            server.stop(0);
        }
    }

    @Test
    void laterMatchingConversionReplacesAnOlderSignedTexture() throws Exception {
        byte[] fingerprint = BedrockSkinFingerprint.forClassicSkin(new byte[64 * 64 * 4],
                64, 64).orElseThrow();
        String currentHash = BedrockSkinFingerprint.read(fingerprint).orElseThrow()
                .iterator().next();
        String texture = "{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/"
                + "82e3966bcbad80d276bcf071b00c4def0f32fa152fed762b6349d6656d47589a\"}}}";
        String value = Base64.getEncoder().encodeToString(texture.getBytes(StandardCharsets.UTF_8));
        AtomicReference<String> availableHash = new AtomicReference<>("f".repeat(64));
        AtomicInteger requests = new AtomicInteger();
        Set<String> cacheKeys = ConcurrentHashMap.newKeySet();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/skin/123", exchange -> {
            requests.incrementAndGet();
            cacheKeys.add(exchange.getRequestURI().getRawQuery());
            byte[] body = ("{\"hash\":\"" + availableHash.get() + "\",\"value\":\""
                    + value + "\",\"signature\":\"signed\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

        BlockingQueue<Runnable> retries = new LinkedBlockingQueue<>();
        CountDownLatch applied = new CountDownLatch(1);
        AtomicReference<ProfileProperty> property = new AtomicReference<>(
                new ProfileProperty("textures", "older-signed-texture", "older-signature"));
        PlayerProfile profile = proxy(PlayerProfile.class, (method, arguments) -> switch (method) {
            case "getProperties" -> Set.of(property.get());
            case "setProperty" -> {
                property.set((ProfileProperty) arguments[0]);
                yield null;
            }
            default -> throw new AssertionError(method);
        });
        EntityScheduler scheduler = proxy(EntityScheduler.class, (method, arguments) -> {
            if (method.equals("execute")) {
                ((Runnable) arguments[1]).run();
                return true;
            }
            if (method.equals("runDelayed")) {
                ScheduledTask task = proxy(ScheduledTask.class, (name, ignored) ->
                        name.equals("cancel") ? ScheduledTask.CancelledState.CANCELLED_BY_CALLER : null);
                @SuppressWarnings("unchecked")
                java.util.function.Consumer<ScheduledTask> action =
                        (java.util.function.Consumer<ScheduledTask>) arguments[1];
                retries.add(() -> action.accept(task));
                return task;
            }
            throw new AssertionError(method);
        });
        UUID uuid = UUID.randomUUID();
        Player player = proxy(Player.class, (method, arguments) -> switch (method) {
            case "getScheduler" -> scheduler;
            case "isOnline" -> true;
            case "getUniqueId" -> uuid;
            case "getPlayerProfile" -> profile;
            case "setPlayerProfile" -> {
                applied.countDown();
                yield null;
            }
            case "getName" -> "BedrockPlayer";
            default -> throw new AssertionError(method);
        });
        Plugin plugin = proxy(Plugin.class, (method, arguments) -> null);
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/skin/");
        ConvertedBedrockSkinLookup lookup =
                new ConvertedBedrockSkinLookup(HttpClient.newHttpClient(), endpoint);
        BedrockSkinAdapter adapter = new BedrockSkinAdapter(plugin, () -> RuntimeState.READY,
                ignored -> "123", lookup, ignored -> player, Logger.getAnonymousLogger());

        try {
            adapter.onJoin(new PlayerJoinEvent(player, Component.empty()));
            adapter.onPluginMessageReceived(BedrockSkinFingerprint.CHANNEL, player, fingerprint);

            Runnable retry = retries.poll(3, TimeUnit.SECONDS);
            assertNotNull(retry, "A stale conversion must schedule a bounded recheck.");
            assertEquals("older-signed-texture", property.get().getValue());

            availableHash.set(currentHash);
            retry.run();
            assertTrue(applied.await(3, TimeUnit.SECONDS));
            assertEquals(2, requests.get());
            assertEquals(2, cacheKeys.size());
            assertEquals(value, property.get().getValue());
        } finally {
            adapter.close();
            server.stop(0);
        }
    }

    @Test
    void shutdownCancelsAnInflightJoinLookupBeforeItCanTouchThePlayerProfile() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/skin/123", exchange -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(404, -1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        UUID uuid = UUID.randomUUID();
        AtomicInteger executions = new AtomicInteger();
        EntityScheduler scheduler = proxy(EntityScheduler.class, (method, arguments) -> {
            if (method.equals("execute")) {
                executions.incrementAndGet();
                ((Runnable) arguments[1]).run();
                return true;
            }
            throw new AssertionError(method);
        });
        Player player = proxy(Player.class, (method, arguments) -> switch (method) {
            case "getScheduler" -> scheduler;
            case "isOnline" -> true;
            case "getUniqueId" -> uuid;
            default -> throw new AssertionError("A cancelled lookup touched " + method);
        });
        Plugin plugin = proxy(Plugin.class, (method, arguments) -> null);
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/skin/");
        ConvertedBedrockSkinLookup lookup =
                new ConvertedBedrockSkinLookup(HttpClient.newHttpClient(), endpoint);
        BedrockSkinAdapter adapter = new BedrockSkinAdapter(plugin, () -> RuntimeState.READY,
                ignored -> "123", lookup, Logger.getAnonymousLogger());

        try {
            adapter.onJoin(new PlayerJoinEvent(player, Component.empty()));
            byte[] fingerprint = BedrockSkinFingerprint.forClassicSkin(new byte[64 * 64 * 4],
                    64, 64).orElseThrow();
            adapter.onPluginMessageReceived(BedrockSkinFingerprint.CHANNEL, player, fingerprint);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            adapter.close();
            assertEquals(1, executions.get());
        } finally {
            release.countDown();
            adapter.close();
            server.stop(0);
        }
    }

    @FunctionalInterface
    private interface Invocation {
        Object call(String method, Object[] arguments);
    }
}
