package com.omegarealm.core.player;

import com.omegarealm.core.authorization.*;
import com.omegarealm.core.command.CommandAuditEvent;
import com.omegarealm.core.command.CommandAuditEvent.Category;
import com.omegarealm.core.command.CommandAuditEvent.Outcome;
import com.omegarealm.core.database.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.omegarealm.core.player.PersistenceException.Kind.*;

/**
 * Retains one checked-out connection and a session advisory lock for the server ID.
 * All writes use that exact connection: a disconnected former owner cannot write through
 * a fresh pooled connection. No executor, timer, or auto-reconnect is hidden in this adapter.
 */
public final class PostgresPlayerSessionRepository implements PlayerSessionRepository {

    private static final int LOCK_NAMESPACE = 1330464583;
    private static final int QUERY_TIMEOUT_SECONDS = 5;

    private final Connection connection;
    private final Database database;
    private final String serverId;
    private final int serverKey;
    private final Thread ownerThread;
    private boolean usable = true;
    private boolean closed;
    private boolean recoveryComplete;
    private boolean activityStarted;

    private PostgresPlayerSessionRepository(Database database, Connection connection, String serverId, int serverKey) {
        this.database = database;
        this.connection = connection;
        this.serverId = serverId;
        this.serverKey = serverKey;
        ownerThread = Thread.currentThread();
    }

    /**
     * The caller owns the returned repository and must close it before closing the pool.
     */
    public static PostgresPlayerSessionRepository acquire(Database database, String serverId)
            throws PersistenceException {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(serverId, "serverId");
        if (!serverId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("Invalid server ID.");
        }
        checkInterrupted();

        Connection borrowed = null;
        boolean transferred = false;
        try {
            borrowed = database.openConnection();
            int key;
            try (PreparedStatement statement = prepare(borrowed, """
                    INSERT INTO omega_servers (server_id) VALUES (?)
                    ON CONFLICT (server_id) DO UPDATE SET server_id = EXCLUDED.server_id
                    RETURNING id
                    """)) {
                statement.setString(1, serverId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    key = result.getInt(1);
                }
            }

            try (PreparedStatement statement = prepare(borrowed, "SELECT pg_try_advisory_lock(?, ?)")) {
                statement.setInt(1, LOCK_NAMESPACE);
                statement.setInt(2, key);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    if (!result.getBoolean(1)) {
                        throw new PersistenceException(SERVER_ALREADY_OWNED,
                                "Player persistence server ID is already owned.");
                    }
                }
            }

            borrowed.setAutoCommit(false);
            checkInterrupted();
            PostgresPlayerSessionRepository repository =
                    new PostgresPlayerSessionRepository(database, borrowed, serverId, key);
            transferred = true;
            return repository;
        } catch (SQLException failure) {
            throw PersistenceException.database("ownership acquisition", failure);
        } catch (RuntimeException failure) {
            throw new PersistenceException(DATABASE_FAILURE, "Unexpected player persistence ownership failure.");
        } finally {
            if (!transferred && borrowed != null) {
                // Closing a pooled handle does not release session advisory locks. Discard the
                // physical connection on partial initialization, even if lock acquisition failed.
                discard(database, borrowed);
            }
        }
    }

    private static void checkInterrupted() throws PersistenceException {
        if (Thread.currentThread().isInterrupted()) {
            throw new PersistenceException(CANCELLED, "Player persistence operation was cancelled.");
        }
    }

    private static void discard(Database database, Connection connection) throws PersistenceException {
        try {
            database.discardConnection(connection);
        } catch (SQLException failure) {
            throw PersistenceException.database("connection disposal", failure);
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            return statement;
        } catch (SQLException failure) {
            statement.close();
            throw failure;
        }
    }

    private static void timestamp(PreparedStatement statement, int index, Instant instant) throws SQLException {
        statement.setObject(index, OffsetDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static PlayerProfile profile(ResultSet result) throws SQLException {
        return new PlayerProfile(result.getObject("uuid", UUID.class), result.getString("username"),
                instant(result, "first_seen_at"), instant(result, "last_seen_at"));
    }

    @Override
    public LoadedPlayer loadPlayer(UUID playerUuid) throws PersistenceException {
        Objects.requireNonNull(playerUuid, "playerUuid");
        return transaction("player preparation", () -> selectLoadedPlayer(playerUuid));
    }

    @Override
    public Map<UUID, AuthorizationSnapshot> loadAuthorizations(List<UUID> playerUuids) throws PersistenceException {
        List<UUID> identities = List.copyOf(playerUuids);
        if (identities.size() > AuthorizationRefreshLimits.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Authorization batch exceeds its bound.");
        }
        return transaction("authorization refresh", () -> selectAuthorizations(identities, false));
    }

    private Map<UUID, AuthorizationSnapshot> selectAuthorizations(List<UUID> identities, boolean lock)
            throws SQLException {
        Map<UUID, AuthorizationSnapshot> result = new HashMap<>();
        var values = connection.createArrayOf("uuid", identities.toArray());
        try (PreparedStatement statement = prepare(connection, """
                SELECT player_uuid, role, revision FROM player_authorizations
                WHERE player_uuid = ANY (?) ORDER BY player_uuid
                """ + (lock ? " FOR UPDATE" : ""))) {
            if (!lock) {
                statement.setQueryTimeout(2);
            }
            statement.setArray(1, values);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID uuid = rows.getObject("player_uuid", UUID.class);
                    long revision = rows.getLong("revision");
                    if (rows.wasNull()) {
                        throw new IllegalStateException("Authorization revision is missing.");
                    }
                    result.put(uuid, new AuthorizationSnapshot(uuid, Role.valueOf(rows.getString("role")), revision));
                }
            }
        } finally {
            values.free();
        }
        return Map.copyOf(result);
    }

    @Override
    public RoleChangeResult changeRole(RoleChangeRequest request) throws PersistenceException {
        Objects.requireNonNull(request, "request");
        requireRecovered();

        // This event survives a later failed mutation. No role write begins unless it commits.
        transaction("command audit acceptance", () -> {
            insertCommandAudit(new CommandAuditEvent(request.operationId(), request.actor(),
                    Category.MANAGEMENT, "role.set", Outcome.QUEUED,
                    request.targetUuid(), request.role(), null));
            return null;
        });

        return transaction("role change", () -> {
            if (request.actor() instanceof AuthorizationActor.Untrusted) {
                return auditedRoleResult(request, request.actor(),
                        new RoleChangeResult(RoleChangeResult.Status.DENIED, null, null));
            }
            List<UUID> identities = request.actor() instanceof AuthorizationActor.Player actor
                    ? List.of(request.targetUuid(), actor.uuid()) : List.of(request.targetUuid());
            // Every writer locks actor and target in UUID order to serialize authority changes.
            Map<UUID, AuthorizationSnapshot> locked = selectAuthorizations(identities, true);
            AuthorizationSnapshot target = locked.get(request.targetUuid());
            if (target == null) {
                // Validate profile/authority in one statement. A first join can commit
                // between statements; it must not be mistaken for a missing role row.
                selectLoadedPlayer(request.targetUuid());
                return auditedRoleResult(request, request.actor(),
                        new RoleChangeResult(RoleChangeResult.Status.NOT_FOUND, null, null));
            }
            AuthorizationActor authoritativeActor = request.actor();
            if (request.actor() instanceof AuthorizationActor.Player actor) {
                AuthorizationSnapshot authority = locked.get(actor.uuid());
                if (authority == null) {
                    selectLoadedPlayer(actor.uuid());
                    return auditedRoleResult(request, request.actor(),
                            new RoleChangeResult(RoleChangeResult.Status.DENIED, null, null));
                }
                authoritativeActor = new AuthorizationActor.Player(actor.uuid(), authority.role());
            }
            if (!AuthorizationPolicy.canChangeRole(authoritativeActor,
                    new AuthorizationActor.Player(target.playerUuid(), target.role()), request.role())) {
                return auditedRoleResult(request, authoritativeActor,
                        new RoleChangeResult(RoleChangeResult.Status.DENIED, null, null));
            }
            if (target.role() == request.role()) {
                return auditedRoleResult(request, authoritativeActor,
                        new RoleChangeResult(RoleChangeResult.Status.UNCHANGED, target, null));
            }
            long revision = Math.addExact(target.revision(), 1);
            UUID auditId = UUID.randomUUID();
            try (PreparedStatement statement = prepare(connection, """
                    UPDATE player_authorizations SET role = ?, revision = ? WHERE player_uuid = ?
                    """)) {
                statement.setString(1, request.role().name());
                statement.setLong(2, revision);
                statement.setObject(3, target.playerUuid());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Role change target disappeared.");
                }
            }
            try (PreparedStatement statement = prepare(connection, """
                    INSERT INTO role_change_audit
                        (id, server_id, actor_kind, actor_uuid, actor_role, target_uuid,
                         previous_role, new_role, previous_revision, new_revision, reason)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setObject(1, auditId);
                statement.setString(2, serverId);
                boolean playerActor = authoritativeActor instanceof AuthorizationActor.Player;
                statement.setString(3, playerActor ? "PLAYER" : "LOCAL_CONSOLE");
                statement.setObject(4, playerActor ? ((AuthorizationActor.Player) authoritativeActor).uuid() : null);
                statement.setString(5, playerActor ? ((AuthorizationActor.Player) authoritativeActor).role().name() : null);
                statement.setObject(6, target.playerUuid());
                statement.setString(7, target.role().name());
                statement.setString(8, request.role().name());
                statement.setLong(9, target.revision());
                statement.setLong(10, revision);
                statement.setString(11, request.reason());
                statement.executeUpdate();
            }
            return auditedRoleResult(request, authoritativeActor,
                    new RoleChangeResult(RoleChangeResult.Status.CHANGED,
                            new AuthorizationSnapshot(target.playerUuid(), request.role(), revision), auditId));
        });
    }

    private RoleChangeResult auditedRoleResult(RoleChangeRequest request, AuthorizationActor actor,
                                               RoleChangeResult result) throws SQLException {
        insertCommandAudit(new CommandAuditEvent(request.operationId(), actor, Category.MANAGEMENT,
                "role.set", Outcome.valueOf(result.status().name()),
                request.targetUuid(), request.role(), result.auditId()));
        return result;
    }

    @Override
    public void appendCommandAudit(CommandAuditEvent event) throws PersistenceException {
        Objects.requireNonNull(event, "event");
        requireRecovered();
        transaction("command audit", () -> {
            insertCommandAudit(event);
            return null;
        });
    }

    private void insertCommandAudit(CommandAuditEvent event) throws SQLException {
        try (PreparedStatement statement = prepare(connection, """
                INSERT INTO command_audit
                    (id, operation_id, server_id, category, command_path, actor_kind,
                     actor_uuid, actor_role, target_uuid, requested_role, outcome, role_change_audit_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, event.operationId());
            statement.setString(3, serverId);
            statement.setString(4, event.category().name());
            statement.setString(5, event.commandPath());
            String actorKind = switch (event.actor()) {
                case AuthorizationActor.LocalConsole ignored -> "LOCAL_CONSOLE";
                case AuthorizationActor.Player ignored -> "PLAYER";
                case AuthorizationActor.Untrusted ignored -> "UNTRUSTED";
            };
            statement.setString(6, actorKind);
            statement.setObject(7, event.actor() instanceof AuthorizationActor.Player actor ? actor.uuid() : null);
            statement.setString(8, event.actor() instanceof AuthorizationActor.Player actor
                    ? actor.role().name() : null);
            statement.setObject(9, event.targetUuid());
            statement.setString(10, event.requestedRole() == null ? null : event.requestedRole().name());
            statement.setString(11, event.outcome().name());
            statement.setObject(12, event.roleChangeAuditId());
            statement.executeUpdate();
        }
    }

    @Override
    public Optional<PlayerProfile> findPlayer(UUID playerUuid) throws PersistenceException {
        Objects.requireNonNull(playerUuid, "playerUuid");
        return transaction("profile load", () -> selectPlayer(playerUuid));
    }

    @Override
    public Optional<PlayerSession> findSession(UUID sessionId) throws PersistenceException {
        Objects.requireNonNull(sessionId, "sessionId");
        return transaction("session load", () -> selectSession(sessionId));
    }

    @Override
    public PlayerProfile join(SessionStart start) throws PersistenceException {
        Objects.requireNonNull(start, "start");
        requireRecovered();
        activityStarted = true;
        return transaction("join", () -> {
            Optional<PlayerSession> existing = selectSession(start.sessionId());
            if (existing.isPresent()) {
                PlayerSession session = existing.get();
                if (!session.playerUuid().equals(start.playerUuid()) || session.endReason() != null
                        || !session.joinedAt().equals(start.joinedAt())) {
                    throw new PersistenceException(SESSION_CONFLICT, "Player persistence session token conflicts.");
                }

                PlayerProfile player = selectLoadedPlayer(start.playerUuid()).storedProfile().orElseThrow();
                if (!player.username().equals(start.username())) {
                    throw new PersistenceException(SESSION_CONFLICT, "Player persistence session metadata conflicts.");
                }
                return player;
            }

            initializeNewPlayer(start);

            PlayerProfile player;
            try (PreparedStatement statement = prepare(connection, """
                    INSERT INTO players (uuid, username, first_seen_at, last_seen_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (uuid) DO UPDATE SET
                        username = CASE WHEN EXCLUDED.last_seen_at >= players.last_seen_at
                                        THEN EXCLUDED.username ELSE players.username END,
                        last_seen_at = GREATEST(players.last_seen_at, EXCLUDED.last_seen_at),
                        updated_at = NOW()
                    RETURNING uuid, username, first_seen_at, last_seen_at
                    """)) {
                statement.setObject(1, start.playerUuid());
                statement.setString(2, start.username());
                timestamp(statement, 3, start.joinedAt());
                timestamp(statement, 4, start.joinedAt());
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    player = profile(result);
                }
            }

            try (PreparedStatement statement = prepare(connection, """
                    INSERT INTO player_sessions (id, player_uuid, server_id, joined_at)
                    VALUES (?, ?, ?, ?)
                    """)) {
                statement.setObject(1, start.sessionId());
                statement.setObject(2, start.playerUuid());
                statement.setString(3, serverId);
                timestamp(statement, 4, start.joinedAt());
                statement.executeUpdate();
            }
            return player;
        });
    }

    @Override
    public boolean endSession(UUID sessionId, Instant leftAt, SessionEnd reason) throws PersistenceException {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(leftAt, "leftAt");
        if (reason != SessionEnd.QUIT && reason != SessionEnd.SHUTDOWN) {
            throw new IllegalArgumentException("Session closure requires QUIT or SHUTDOWN.");
        }
        requireRecovered();
        activityStarted = true;
        Instant endedAt = leftAt.truncatedTo(ChronoUnit.MICROS);

        return transaction("session closure", () -> {
            Optional<PlayerSession> existing = selectSession(sessionId);
            if (existing.isEmpty() || existing.get().endReason() != null) {
                return false;
            }
            PlayerSession session = existing.get();
            if (endedAt.isBefore(session.joinedAt())) {
                throw new PersistenceException(INVALID_SESSION_END,
                        "Player persistence leave time precedes join time.");
            }

            try (PreparedStatement statement = prepare(connection, """
                    UPDATE player_sessions SET left_at = ?, end_reason = ?
                    WHERE id = ? AND server_id = ? AND end_reason IS NULL
                    """)) {
                timestamp(statement, 1, endedAt);
                statement.setString(2, reason.name());
                statement.setObject(3, sessionId);
                statement.setString(4, serverId);
                statement.executeUpdate();
            }

            try (PreparedStatement statement = prepare(connection, """
                    UPDATE players SET last_seen_at = GREATEST(last_seen_at, ?), updated_at = NOW()
                    WHERE uuid = ?
                    """)) {
                timestamp(statement, 1, endedAt);
                statement.setObject(2, session.playerUuid());
                statement.executeUpdate();
            }
            return true;
        });
    }

    /**
     * Startup-only recovery: invoke before admitting any connections for this owner.
     */
    @Override
    public int recoverInterrupted(Instant recoveredAt) throws PersistenceException {
        Objects.requireNonNull(recoveredAt, "recoveredAt");
        requireOwnerThread();
        if (activityStarted) {
            throw new IllegalStateException("Session recovery is only allowed before player activity.");
        }
        int recovered = transaction("session recovery", () -> {
            try (PreparedStatement statement = prepare(connection, """
                    UPDATE player_sessions SET end_reason = 'INTERRUPTED', recovered_at = ?
                    WHERE server_id = ? AND end_reason IS NULL
                    """)) {
                timestamp(statement, 1, recoveredAt);
                statement.setString(2, serverId);
                return statement.executeUpdate();
            }
        });
        recoveryComplete = true;
        return recovered;
    }

    @Override
    public void checkOwnership() throws PersistenceException {
        transaction("ownership check", () -> null);
    }

    private Optional<PlayerProfile> selectPlayer(UUID playerUuid) throws SQLException {
        try (PreparedStatement statement = prepare(connection, """
                SELECT uuid, username, first_seen_at, last_seen_at FROM players WHERE uuid = ?
                """)) {
            statement.setObject(1, playerUuid);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(profile(result)) : Optional.empty();
            }
        }
    }

    private LoadedPlayer selectLoadedPlayer(UUID playerUuid) throws SQLException {
        try (PreparedStatement statement = prepare(connection, """
                SELECT p.uuid, p.username, p.first_seen_at, p.last_seen_at, a.role, a.revision
                FROM players p LEFT JOIN player_authorizations a ON a.player_uuid = p.uuid
                WHERE p.uuid = ?
                """)) {
            statement.setObject(1, playerUuid);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return new LoadedPlayer(Optional.empty(), AuthorizationSnapshot.initial(playerUuid));
                }

                String role = result.getString("role");
                long revision = result.getLong("revision");
                if (role == null || result.wasNull()) {
                    throw new IllegalStateException("Existing player authorization is missing.");
                }
                AuthorizationSnapshot authorization = new AuthorizationSnapshot(playerUuid,
                        Role.valueOf(role), revision);
                return new LoadedPlayer(Optional.of(profile(result)), authorization);
            }
        }
    }

    private void initializeNewPlayer(SessionStart start) throws SQLException {
        boolean inserted;
        try (PreparedStatement statement = prepare(connection, """
                INSERT INTO players (uuid, username, first_seen_at, last_seen_at)
                VALUES (?, ?, ?, ?) ON CONFLICT (uuid) DO NOTHING RETURNING uuid
                """)) {
            statement.setObject(1, start.playerUuid());
            statement.setString(2, start.username());
            timestamp(statement, 3, start.joinedAt());
            timestamp(statement, 4, start.joinedAt());
            try (ResultSet result = statement.executeQuery()) {
                inserted = result.next();
            }
        }

        if (inserted) {
            try (PreparedStatement statement = prepare(connection, """
                    INSERT INTO player_authorizations (player_uuid, role, revision)
                    VALUES (?, 'PLAYER', 0)
                    """)) {
                statement.setObject(1, start.playerUuid());
                statement.executeUpdate();
            }
        } else {
            // An existing identity with missing authority is corruption, not a new player.
            // Never repair it silently or overwrite an elevated role on reconnect.
            selectLoadedPlayer(start.playerUuid());
        }
    }

    private Optional<PlayerSession> selectSession(UUID sessionId) throws SQLException {
        try (PreparedStatement statement = prepare(connection, """
                SELECT id, player_uuid, server_id, joined_at, left_at, end_reason, recovered_at
                FROM player_sessions WHERE id = ? AND server_id = ?
                """)) {
            statement.setObject(1, sessionId);
            statement.setString(2, serverId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                String reason = result.getString("end_reason");
                return Optional.of(new PlayerSession(result.getObject("id", UUID.class),
                        result.getObject("player_uuid", UUID.class), result.getString("server_id"),
                        instant(result, "joined_at"), instant(result, "left_at"),
                        reason == null ? null : SessionEnd.valueOf(reason), instant(result, "recovered_at")));
            }
        }
    }

    private void verifyLock() throws SQLException, PersistenceException {
        try (PreparedStatement statement = prepare(connection, """
                SELECT EXISTS (
                    SELECT 1 FROM pg_locks
                    WHERE locktype = 'advisory' AND pid = pg_backend_pid()
                      AND database = (SELECT oid FROM pg_database WHERE datname = current_database())
                      AND classid::bigint = ? AND objid::bigint = ? AND objsubid = 2
                      AND mode = 'ExclusiveLock' AND granted
                )
                """)) {
            statement.setInt(1, LOCK_NAMESPACE);
            statement.setInt(2, serverKey);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (!result.getBoolean(1)) {
                    usable = false;
                    throw new PersistenceException(OWNERSHIP_LOST, "Player persistence server ownership was lost.");
                }
            }
        }
    }

    private <T> T transaction(String phase, SqlOperation<T> operation) throws PersistenceException {
        requireOwnerThread();
        if (closed || !usable) {
            throw new PersistenceException(OWNERSHIP_LOST, "Player persistence owner is unavailable.");
        }
        try {
            checkInterrupted();
            verifyLock();
            T result = operation.execute();
            checkInterrupted();
            connection.commit();
            return result;
        } catch (SQLException failure) {
            rollback();
            if (phase.equals("join") && "23505".equals(failure.getSQLState())) {
                throw new PersistenceException(SESSION_CONFLICT, "Player persistence active session conflicts.");
            }
            usable = false;
            throw PersistenceException.database(phase, failure);
        } catch (PersistenceException | RuntimeException failure) {
            rollback();
            if (failure instanceof PersistenceException controlled) {
                throw controlled;
            }
            usable = false;
            throw new PersistenceException(DATABASE_FAILURE, "Unexpected player persistence failure.");
        }
    }

    private void rollback() throws PersistenceException {
        try {
            connection.rollback();
        } catch (SQLException failure) {
            usable = false;
            throw PersistenceException.database("rollback", failure);
        }
    }

    @Override
    public void close() throws PersistenceException {
        requireOwnerThread();
        if (closed) {
            return;
        }
        closed = true;

        boolean interrupted = Thread.interrupted();
        try {
            if (!usable) {
                discard(database, connection);
                return;
            }
            try {
                connection.rollback();
                connection.setAutoCommit(true);
                try (PreparedStatement statement = prepare(connection, "SELECT pg_advisory_unlock(?, ?)")) {
                    statement.setInt(1, LOCK_NAMESPACE);
                    statement.setInt(2, serverKey);
                    try (ResultSet result = statement.executeQuery()) {
                        result.next();
                        if (!result.getBoolean(1)) {
                            throw new SQLException("Ownership unavailable", "08006");
                        }
                    }
                }
                connection.close();
            } catch (SQLException failure) {
                discard(database, connection);
                throw PersistenceException.database("ownership release", failure);
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Player persistence requires its owning I/O thread.");
        }
    }

    private void requireRecovered() {
        requireOwnerThread();
        if (!recoveryComplete) {
            throw new IllegalStateException("Session recovery must complete before player activity.");
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T execute() throws SQLException, PersistenceException;
    }
}
