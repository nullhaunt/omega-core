package com.omegarealm.core.player;

import java.time.Instant;
import java.util.UUID;

/**
 * An interrupted session has a recovery timestamp and an unknown (null) leave time.
 * An open session has no end reason, leave time, or recovery timestamp.
 */
public record PlayerSession(UUID id, UUID playerUuid, String serverId, Instant joinedAt,
                            Instant leftAt, SessionEnd endReason, Instant recoveredAt) {
}
