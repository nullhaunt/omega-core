package com.omegarealm.core.player;

import com.omegarealm.core.authorization.Role;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PublicPlayerProfileTest {

    @Test
    void nicknameDoesNotReplaceCanonicalIdentityOrStaffDesignation() {
        PlayerProfile identity = new PlayerProfile(UUID.randomUUID(), "ActualName",
                Instant.parse("2026-09-28T00:00:00Z"), Instant.parse("2026-09-28T00:00:00Z"));
        PublicPlayerProfile profile = new PublicPlayerProfile(identity, Role.MODERATOR, Optional.of("Aeron"));

        assertEquals(identity, profile.identity());
        assertEquals(Optional.of("Aeron"), profile.nickname());
        assertEquals(Optional.of("Moderator"), profile.staffDesignation());
        assertThrows(IllegalArgumentException.class,
                () -> new PublicPlayerProfile(identity, Role.PLAYER, Optional.of("<red>Aeron")));
        assertThrows(IllegalArgumentException.class,
                () -> new PublicPlayerProfile(identity, Role.PLAYER, Optional.of("Moderator")));
    }

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
