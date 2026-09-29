package com.omegarealm.core.player.skin;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockSkinFingerprintTest {

    @Test
    void acceptsRawAndGeyserCleanedHashesForTheSameClassicSkin() throws Exception {
        byte[] pixels = new byte[64 * 64 * 4];
        pixels[0] = 42; // Geyser clears this unused pixel before converting.
        byte[] message = BedrockSkinFingerprint.forClassicSkin(pixels, 64, 64).orElseThrow();
        var hashes = BedrockSkinFingerprint.read(message).orElseThrow();

        assertTrue(hashes.contains(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(pixels))));
        assertTrue(hashes.contains(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(new byte[pixels.length]))));
        assertFalse(hashes.contains("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"));
    }

    @Test
    void rejectsUnsupportedSkinDimensionsAndMalformedMessages() {
        assertTrue(BedrockSkinFingerprint.forClassicSkin(new byte[128 * 128 * 4],
                128, 128).isEmpty());
        assertTrue(BedrockSkinFingerprint.read(new byte[97]).isEmpty());
        assertTrue(BedrockSkinFingerprint.read(new byte[2]).isEmpty());
    }
}
