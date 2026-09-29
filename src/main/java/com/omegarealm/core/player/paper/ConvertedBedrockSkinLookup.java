package com.omegarealm.core.player.paper;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Geyser's most recently converted Mojang-signed texture. Geyser owns conversion;
 * this lookup only recovers delivery when Floodgate leaves its join placeholder in place.
 */
final class ConvertedBedrockSkinLookup implements AutoCloseable {

    private static final URI SKIN_API = URI.create("https://api.geysermc.org/v2/skin/");
    private static final int MAX_BODY_BYTES = 32 * 1024;
    private static final int MAX_VALUE_LENGTH = 16 * 1024;
    private static final int MAX_SIGNATURE_LENGTH = 4 * 1024;
    private static final Pattern TEXTURE_URL = Pattern.compile(
            "https?://textures\\.minecraft\\.net/texture/([a-fA-F0-9]{64})");
    private static final String FLOODGATE_PLACEHOLDER =
            "31f477eb1a7beee631c2ca64d06f8f68fa93a3386d04452ab27f43acdf1b60cb";

    private final HttpClient client;
    private final URI endpoint;
    private final AtomicLong cacheKeys = new AtomicLong();

    ConvertedBedrockSkinLookup() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), SKIN_API);
    }

    ConvertedBedrockSkinLookup(HttpClient client, URI endpoint) {
        this.client = client;
        this.endpoint = endpoint;
    }

    static Optional<SignedSkin> parse(String body) {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                return Optional.empty();
            }

            JsonObject object = parsed.getAsJsonObject();
            String value = string(object, "value");
            String signature = string(object, "signature");
            String hash = string(object, "hash");
            if (value == null || value.isBlank() || value.length() > MAX_VALUE_LENGTH
                    || signature == null || signature.isBlank() || signature.length() > MAX_SIGNATURE_LENGTH
                    || hash == null || !hash.matches("(?i:[a-f0-9]{64})")) {
                return Optional.empty();
            }

            String decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            JsonElement textureData = JsonParser.parseString(decoded);
            if (!textureData.isJsonObject()) {
                return Optional.empty();
            }

            JsonObject textures = textureData.getAsJsonObject().getAsJsonObject("textures");
            JsonObject skin = textures == null ? null : textures.getAsJsonObject("SKIN");
            String url = skin == null ? null : string(skin, "url");
            Matcher texture = url == null ? null : TEXTURE_URL.matcher(url);
            if (texture == null || !texture.matches()
                    || FLOODGATE_PLACEHOLDER.equalsIgnoreCase(texture.group(1))) {
                return Optional.empty();
            }

            return Optional.of(new SignedSkin(value, signature, hash.toLowerCase(Locale.ROOT)));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    CompletableFuture<Optional<SignedSkin>> fetch(String xuid) {
        if (xuid == null || !xuid.matches("[0-9]{1,20}")) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        // The public endpoint can keep serving a previous conversion after Geyser
        // uploads a changed skin. A unique query requests a fresh cache entry.
        URI skinUri = endpoint.resolve(xuid + "?omega-refresh=" + System.currentTimeMillis()
                + "-" + cacheKeys.incrementAndGet());
        HttpRequest request = HttpRequest.newBuilder(skinUri)
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "OmegaCore/BedrockSkin")
                .GET()
                .build();

        CompletableFuture<HttpResponse<byte[]>> exchange = client.sendAsync(
                request, ignored -> new BoundedBodySubscriber(MAX_BODY_BYTES));
        CompletableFuture<Optional<SignedSkin>> result = new CompletableFuture<>();
        exchange.whenComplete((response, failure) -> {
            if (failure != null) {
                result.completeExceptionally(failure);
            } else {
                try {
                    if (response.statusCode() == 200) {
                        result.complete(parse(new String(response.body(), StandardCharsets.UTF_8)));
                    } else if (response.statusCode() == 204 || response.statusCode() == 404) {
                        result.complete(Optional.empty());
                    } else {
                        result.completeExceptionally(
                                new IOException("Converted Bedrock skin service is unavailable."));
                    }
                } catch (RuntimeException invalid) {
                    result.completeExceptionally(invalid);
                }
            }
        });
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                exchange.cancel(true);
            }
        });
        return result;
    }

    @Override
    public void close() throws InterruptedException {
        client.shutdownNow();
        if (!client.awaitTermination(Duration.ofSeconds(2))) {
            throw new IllegalStateException("Bedrock skin HTTP client did not stop within two seconds.");
        }
    }

    record SignedSkin(String value, String signature, String hash) {
    }

    /**
     * Reject oversized responses while they are streamed, before retaining their body.
     */
    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private BoundedBodySubscriber(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletableFuture<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("Converted skin response is too large."));
                    return;
                }

                byte[] copy = new byte[chunk.remaining()];
                chunk.get(copy);
                bytes.writeBytes(copy);
            }

            subscription.request(1);
        }

        @Override
        public void onError(Throwable failure) {
            body.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }
}
