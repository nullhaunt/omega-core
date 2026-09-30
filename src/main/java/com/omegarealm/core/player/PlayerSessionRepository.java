package com.omegarealm.core.player;

import com.omegarealm.core.authorization.AuthorizationSnapshot;
import com.omegarealm.core.authorization.RoleChangeRequest;
import com.omegarealm.core.authorization.RoleChangeResult;
import com.omegarealm.core.command.CommandAuditEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Blocking persistence boundary. Acquire, use, and close on one owned I/O worker;
 * never invoke on a Minecraft tick/region thread. The repository does not own the pool.
 */
public interface PlayerSessionRepository extends AutoCloseable {

    /**
     * Focused profile view sharing this owner's connection, transactions, and lifetime.
     */
    PlayerProfileRepository profiles();

    /**
     * Loads profile and authorization consistently; does not create either record.
     */
    LoadedPlayer loadPlayer(UUID playerUuid) throws PersistenceException;

    /**
     * Reads up to 64 identities in one statement; missing rows are not substituted.
     */
    Map<UUID, AuthorizationSnapshot> loadAuthorizations(List<UUID> playerUuids) throws PersistenceException;

    /**
     * Persists acceptance first, then rechecks authoritative actor/target roles and
     * commits the outcome event with any role change and mutation audit.
     */
    RoleChangeResult changeRole(RoleChangeRequest request) throws PersistenceException;

    /**
     * Appends one significant command invocation on the owned I/O worker.
     */
    void appendCommandAudit(CommandAuditEvent event) throws PersistenceException;

    Optional<PlayerProfile> findPlayer(UUID playerUuid) throws PersistenceException;

    /**
     * Resolves a persisted UUID or current stored username. Two username matches are
     * enough to reject ambiguity without returning a large result set.
     */
    PlayerTargetResolution resolveTarget(PlayerTarget target) throws PersistenceException;

    Optional<PlayerSession> findSession(UUID sessionId) throws PersistenceException;

    PlayerProfile join(SessionStart start) throws PersistenceException;

    boolean endSession(UUID sessionId, Instant leftAt, SessionEnd reason) throws PersistenceException;

    int recoverInterrupted(Instant recoveredAt) throws PersistenceException;

    void checkOwnership() throws PersistenceException;

    @Override
    void close() throws PersistenceException;
}
