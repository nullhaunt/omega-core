package com.omegarealm.core.player.skin;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * A small plugin-message contract for comparing a current 64x64 Bedrock skin with
 * Geyser's converted skin. Only hashes cross the bridge; no image bytes are sent.
 */
public final class BedrockSkinFingerprint {

    public static final String CHANNEL = "omegacore:skin";

    private static final int SKIN_BYTES = 64 * 64 * 4;
    private static final int HASH_BYTES = 32;
    private static final int HASH_COUNT = 3;
    private static final byte VERSION = 1;
    private static final int MESSAGE_BYTES = 1 + HASH_COUNT * HASH_BYTES;

    private BedrockSkinFingerprint() {
    }

    public static Optional<byte[]> forClassicSkin(byte[] rgba, int width, int height) {
        if (rgba == null || width != 64 || height != 64 || rgba.length != SKIN_BYTES) {
            return Optional.empty();
        }

        byte[] message = new byte[MESSAGE_BYTES];
        message[0] = VERSION;
        System.arraycopy(hash(rgba), 0, message, 1, HASH_BYTES);

        byte[] cleaned = rgba.clone();
        clearUnusedPixels(cleaned, true);
        System.arraycopy(hash(cleaned), 0, message, 1 + HASH_BYTES, HASH_BYTES);

        cleaned = rgba.clone();
        clearUnusedPixels(cleaned, false);
        System.arraycopy(hash(cleaned), 0, message, 1 + 2 * HASH_BYTES, HASH_BYTES);
        return Optional.of(message);
    }

    public static Optional<Set<String>> read(byte[] message) {
        if (message == null || message.length != MESSAGE_BYTES || message[0] != VERSION) {
            return Optional.empty();
        }

        Set<String> hashes = new HashSet<>();
        for (int index = 0; index < HASH_COUNT; index++) {
            int start = 1 + index * HASH_BYTES;
            hashes.add(HexFormat.of().formatHex(message, start, start + HASH_BYTES));
        }
        return Optional.of(Set.copyOf(hashes));
    }

    private static byte[] hash(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("The JVM does not provide SHA-256.", unavailable);
        }
    }

    // Geyser Global API clears these unused 64x64 regions before hashing its
    // Java conversion. Keep this map aligned with its pixel_cleaner.rs.
    private static void clearUnusedPixels(byte[] rgba, boolean classic) {
        clear(rgba, 0, 8, 0, 8);
        clear(rgba, 24, 40, 0, 8);
        clear(rgba, 56, 64, 0, 8);
        clear(rgba, 0, 4, 16, 20);
        clear(rgba, 12, 20, 16, 20);
        clear(rgba, 36, 44, 16, 20);
        clear(rgba, 0, 4, 32, 36);
        clear(rgba, 12, 20, 32, 36);
        clear(rgba, 36, 44, 32, 36);
        clear(rgba, 0, 4, 48, 52);
        clear(rgba, 12, 20, 48, 52);
        clear(rgba, 28, 36, 48, 52);

        if (classic) {
            clear(rgba, 52, 56, 16, 20);
            clear(rgba, 52, 56, 32, 36);
            clear(rgba, 44, 52, 48, 52);
            clear(rgba, 60, 64, 48, 52);
            clear(rgba, 56, 64, 16, 48);
        } else {
            clear(rgba, 50, 54, 16, 20);
            clear(rgba, 50, 52, 32, 36);
            clear(rgba, 42, 52, 48, 52);
            clear(rgba, 46, 48, 52, 64);
            clear(rgba, 58, 64, 48, 52);
            clear(rgba, 62, 64, 52, 64);
            clear(rgba, 54, 64, 16, 48);
        }
    }

    private static void clear(byte[] rgba, int left, int right, int top, int bottom) {
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int pixel = (y * 64 + x) * 4;
                Arrays.fill(rgba, pixel, pixel + 4, (byte) 0);
            }
        }
    }
}
