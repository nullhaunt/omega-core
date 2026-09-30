package com.omegarealm.core.player;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerTargetTest {

    @Test
    void canonicalUuidAndCurrentNameTokensHaveDistinctTypes() {
        UUID uuid = UUID.randomUUID();
        assertEquals(new PlayerTarget.ByUuid(uuid), PlayerTarget.parse(uuid.toString()));
        assertEquals(new PlayerTarget.ByUuid(uuid), PlayerTarget.parse(uuid.toString().toUpperCase()));
        assertEquals(new PlayerTarget.ByName("Some_Player"), PlayerTarget.parse("Some_Player"));
        assertEquals(new PlayerTarget.ByName(".Unreference"), PlayerTarget.parse(".Unreference"));
        assertEquals(new PlayerTarget.ByName("Aeron_1"), PlayerTarget.parse("Aeron_1"));
        assertEquals(new PlayerTarget.ByName("ab"), PlayerTarget.parse("ab"));
    }

    @Test
    void malformedTargetsCannotFallThroughToANameLookup() {
        for (String invalid : new String[]{"", ".", "1-1-1-1-1", "name with space", "name%",
                "a".repeat(17), "." + "a".repeat(16), "00000000-0000-0000-0000-00000000000z"}) {
            assertThrows(IllegalArgumentException.class, () -> PlayerTarget.parse(invalid));
        }
    }
}
