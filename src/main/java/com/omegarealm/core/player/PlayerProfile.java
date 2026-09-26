package com.omegarealm.core.player;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent identity; timestamps describe successful joins and known disconnects.
 */
public record PlayerProfile(UUID uuid, String username, Instant firstSeenAt, Instant lastSeenAt) {
}
