package com.omegarealm.core.player;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable conflict notice. Delivery and acknowledgement are separate operations.
 */
public record NicknameNotice(UUID id, UUID playerUuid, String removedNickname, Instant createdAt) {

    public NicknameNotice {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(removedNickname, "removedNickname");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
