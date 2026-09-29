package com.omegarealm.core.player.paper;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class ConvertedBedrockSkinLookupTest {

    private static final String PLACEHOLDER =
            "31f477eb1a7beee631c2ca64d06f8f68fa93a3386d04452ab27f43acdf1b60cb";
    private static final String CONVERTED =
            "b833f42ff2ffa01c8a65f9c7099062493e0b519ea9194db33f105e6092c8a55b";
    private static final String HASH = "a52b7685bea723523809e2d3d6d5381199d725183858af9ba0edf027b139c166";

    private static String texture(String id) {
        String value = "{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/"
                + id + "\"}}}";
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsThePixelHashAlongsideTheSignedTexture() {
        String value = texture(CONVERTED);
        String response = "{\"hash\":\"" + HASH + "\",\"is_steve\":true,\"value\":\""
                + value + "\",\"signature\":\"signed\"}";

        var skin = ConvertedBedrockSkinLookup.parse(response).orElseThrow();
        assertEquals(value, skin.value());
        assertEquals("signed", skin.signature());
        assertEquals(HASH, skin.hash());
    }

    @Test
    void rejectsPlaceholderAndUnsignedResponses() {
        String placeholder = texture(PLACEHOLDER);
        assertTrue(ConvertedBedrockSkinLookup.parse("{\"hash\":\"" + HASH + "\",\"value\":\""
                + placeholder + "\",\"signature\":\"signed\"}").isEmpty());
        assertTrue(ConvertedBedrockSkinLookup.parse("{\"hash\":\"" + HASH + "\",\"value\":\""
                + texture(CONVERTED) + "\"}").isEmpty());
        assertTrue(ConvertedBedrockSkinLookup.parse("{\"value\":\""
                + texture(CONVERTED) + "\",\"signature\":\"signed\"}").isEmpty());
        assertTrue(ConvertedBedrockSkinLookup.parse("{\"hash\":\"invalid\",\"value\":\""
                + texture(CONVERTED) + "\",\"signature\":\"signed\"}").isEmpty());
    }

    @Test
    void rejectsOversizedHttpBodyBeforeParsing() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/skin/123", exchange -> {
            byte[] body = new byte[33 * 1024];
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/skin/");
            try (ConvertedBedrockSkinLookup lookup =
                         new ConvertedBedrockSkinLookup(HttpClient.newHttpClient(), endpoint)) {
                CompletionException failure = assertThrows(CompletionException.class,
                        () -> lookup.fetch("123").join());
                assertInstanceOf(java.io.IOException.class, failure.getCause());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rateLimitIsAServiceFailureInsteadOfAnUnavailableSkin() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/skin/123", exchange -> {
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        server.start();

        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/skin/");
            try (ConvertedBedrockSkinLookup lookup =
                         new ConvertedBedrockSkinLookup(HttpClient.newHttpClient(), endpoint)) {
                CompletionException failure = assertThrows(CompletionException.class,
                        () -> lookup.fetch("123").join());
                assertInstanceOf(java.io.IOException.class, failure.getCause());
            }
        } finally {
            server.stop(0);
        }
    }
}
