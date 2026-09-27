package com.omegarealm.core.player;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Blocking persistence boundary. Acquire, use, and close on one owned I/O worker;
 * never invoke on a Minecraft tick/region thread. The repository does not own the pool.
 */
public interface PlayerSessionRepository extends AutoCloseable {

    /**
     * Loads profile and authorization consistently; does not create either record.
     */
    LoadedPlayer loadPlayer(UUID playerUuid) throws PersistenceException;

    Optional<PlayerProfile> findPlayer(UUID playerUuid) throws PersistenceException;

    Optional<PlayerSession> findSession(UUID sessionId) throws PersistenceException;

    PlayerProfile join(SessionStart start) throws PersistenceException;

    boolean endSession(UUID sessionId, Instant leftAt, SessionEnd reason) throws PersistenceException;

    int recoverInterrupted(Instant recoveredAt) throws PersistenceException;

    void checkOwnership() throws PersistenceException;

    @Override
    void close() throws PersistenceException;
}
