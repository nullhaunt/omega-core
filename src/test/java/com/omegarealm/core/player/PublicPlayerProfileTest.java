package com.omegarealm.core.player;

import com.omegarealm.core.authorization.Role;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicPlayerProfileTest {

    @Test
    void staffDesignationAlwaysFollowsTheStoredRole() {
        PlayerProfile identity = new PlayerProfile(UUID.randomUUID(), "Comet",
                Instant.parse("2026-09-28T00:00:00Z"), Instant.parse("2026-09-28T00:00:00Z"));

        assertTrue(new PublicPlayerProfile(identity, Role.PLAYER).staffDesignation().isEmpty());
        assertEquals("Moderator", new PublicPlayerProfile(identity, Role.MODERATOR)
                .staffDesignation().orElseThrow());
        assertEquals("Admin", new PublicPlayerProfile(identity, Role.ADMIN)
                .staffDesignation().orElseThrow());
        assertEquals("Owner", new PublicPlayerProfile(identity, Role.OWNER)
                .staffDesignation().orElseThrow());
    }
}
