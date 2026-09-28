package com.omegarealm.core.player;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerTargetTest {

    @Test
    void canonicalUuidAndCurrentJavaOrFloodgateNamesHaveDistinctTypes() {
        UUID uuid = UUID.randomUUID();
        assertEquals(new PlayerTarget.ByUuid(uuid), PlayerTarget.parse(uuid.toString()));
        assertEquals(new PlayerTarget.ByUuid(uuid), PlayerTarget.parse(uuid.toString().toUpperCase()));
        assertEquals(new PlayerTarget.ByUsername("Some_Player"), PlayerTarget.parse("Some_Player"));
        assertEquals(new PlayerTarget.ByUsername(".Unreference"), PlayerTarget.parse(".Unreference"));
    }

    @Test
    void malformedTargetsCannotFallThroughToANameLookup() {
        for (String invalid : new String[]{"", ".", "1-1-1-1-1", "name with space", "name%",
                "a".repeat(17), "." + "a".repeat(16), "00000000-0000-0000-0000-00000000000z"}) {
            assertThrows(IllegalArgumentException.class, () -> PlayerTarget.parse(invalid));
        }
    }
}
