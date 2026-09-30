package com.omegarealm.core.player;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NicknamePolicyTest {

    @Test
    void acceptsOnlyTheAgreedAlphabetAndLengthWithoutCoercingInput() {
        for (String name : new String[]{"A_1", "Aeron", "1234567890123456"}) {
            assertTrue(NicknamePolicy.valid(name));
        }
        for (String name : new String[]{"", "ab", "12345678901234567", " Aeron", "Aeron ",
                "Ae ron", ".Aeron", "<red>Aeron", "Aéron", "Аeron", "Ae\u200Bron", "Ae\nron"}) {
            assertFalse(NicknamePolicy.valid(name));
        }
        assertFalse(NicknamePolicy.valid(null));
    }

    @Test
    void protectsStaffLabelsAndBothBedrockSpellingsWithAsciiCaseFolding() {
        assertEquals("aeron_1", NicknamePolicy.key("AeRoN_1"));
        assertEquals(Set.of("aeron"), NicknamePolicy.accountKeys("Aeron"));
        assertEquals(Set.of(".aeron", "aeron"), NicknamePolicy.accountKeys(".Aeron"));
        for (String name : new String[]{"CLEAR", "Moderator", "aDmIn", "OWNER"}) {
            assertTrue(NicknamePolicy.reserved(name));
        }
        assertFalse(NicknamePolicy.reserved("Aeron"));
    }
}
