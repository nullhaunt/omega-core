package com.omegarealm.core.player;

import java.util.Objects;
import java.util.UUID;

/**
 * Persistent profile field, independent of connection state and role authority.
 */
public record PlayerNickname(UUID playerUuid, String nickname, long revision) {

    public PlayerNickname {
        Objects.requireNonNull(playerUuid, "playerUuid");
        if (revision < 0 || nickname != null && (!NicknamePolicy.valid(nickname) || NicknamePolicy.reserved(nickname))) {
            throw new IllegalArgumentException("Invalid stored nickname state.");
        }
    }
}
