package com.omegarealm.core.player.paper;

import com.omegarealm.core.RuntimeState;
import com.sun.net.httpserver.HttpServer;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class BedrockSkinAdapterTest {

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, arguments) -> invocation.call(method.getName(), arguments)));
    }

    @Test
    void shutdownCancelsAnInflightRefreshBeforeItCanTouchThePlayerProfile() throws Exception {
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
        EntityScheduler scheduler = proxy(EntityScheduler.class, (method, arguments) -> {
            if (method.equals("execute")) {
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
            var result = adapter.refresh(player);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            adapter.close();
            assertEquals(BedrockSkinAdapter.RefreshResult.CANCELLED, result.get(2, TimeUnit.SECONDS));
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
