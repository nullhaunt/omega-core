package com.omegarealm.core.authorization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoleChangeRequestTest {

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\n", "a\nb", "a\tb", "a\u0000b"})
    void rejectsBlankOrControlContainingReasons(String reason) {
        assertThrows(IllegalArgumentException.class, () -> new RoleChangeRequest(
                new AuthorizationActor.LocalConsole(), UUID.randomUUID(), Role.ADMIN, reason));
    }

    @Test
    void normalizesAndBoundsTheMandatoryReason() {
        var request = new RoleChangeRequest(new AuthorizationActor.LocalConsole(), UUID.randomUUID(),
                Role.ADMIN, "  Staff appointment  ");
        assertEquals("Staff appointment", request.reason());
        assertThrows(IllegalArgumentException.class, () -> new RoleChangeRequest(
                request.actor(), request.targetUuid(), request.role(), "x".repeat(513)));
    }
}
