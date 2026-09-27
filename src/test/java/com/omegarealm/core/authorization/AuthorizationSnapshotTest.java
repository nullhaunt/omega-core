package com.omegarealm.core.authorization;

import com.omegarealm.core.player.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizationSnapshotTest {

    @ParameterizedTest
    @EnumSource(Role.class)
    void preservesLoadedRoleIdentityAndRevision(Role role) {
        UUID uuid = UUID.randomUUID();
        AuthorizationSnapshot snapshot = new AuthorizationSnapshot(uuid, role, Long.MAX_VALUE);

        assertEquals(uuid, snapshot.actor().uuid());
        assertEquals(role, snapshot.actor().role());
        assertEquals(Long.MAX_VALUE, snapshot.revision());
    }

    @Test
    void initialAuthorizationIsPlayerAtRevisionZero() {
        UUID uuid = UUID.randomUUID();
        AuthorizationSnapshot initial = AuthorizationSnapshot.initial(uuid);

        assertEquals(Role.PLAYER, initial.role());
        assertEquals(0, initial.revision());
        assertEquals(uuid, initial.playerUuid());
        assertDoesNotThrow(() -> new LoadedPlayer(Optional.empty(), initial));
    }

    @Test
    void rejectsInvalidSnapshotsRatherThanSubstitutingPlayer() {
        UUID uuid = UUID.randomUUID();
        assertThrows(NullPointerException.class, () -> new AuthorizationSnapshot(null, Role.PLAYER, 0));
        assertThrows(NullPointerException.class, () -> new AuthorizationSnapshot(uuid, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new AuthorizationSnapshot(uuid, Role.ADMIN, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new LoadedPlayer(Optional.empty(), new AuthorizationSnapshot(uuid, Role.ADMIN, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new LoadedPlayer(Optional.empty(), new AuthorizationSnapshot(uuid, Role.PLAYER, 1)));
    }

    @Test
    void profileAuthorizationAndConnectionMustShareIdentity() {
        UUID uuid = UUID.randomUUID();
        PlayerProfile profile = new PlayerProfile(uuid, "Player", Instant.EPOCH, Instant.EPOCH);
        AuthorizationSnapshot snapshot = new AuthorizationSnapshot(uuid, Role.ADMIN, 4);
        LoadedPlayer loaded = new LoadedPlayer(Optional.of(profile), snapshot);
        PlayerAttempt attempt = new PlayerAttempt(UUID.randomUUID(), new PlayerIdentity(uuid, "Player"));
        assertEquals(snapshot, new PreparedPlayer(attempt, loaded.storedProfile(), snapshot).authorization());

        assertThrows(IllegalArgumentException.class,
                () -> new LoadedPlayer(Optional.of(profile), AuthorizationSnapshot.initial(UUID.randomUUID())));
        PlayerAttempt other = new PlayerAttempt(UUID.randomUUID(), new PlayerIdentity(UUID.randomUUID(), "Player"));
        assertThrows(IllegalArgumentException.class,
                () -> new PreparedPlayer(other, loaded.storedProfile(), snapshot));
    }
}
