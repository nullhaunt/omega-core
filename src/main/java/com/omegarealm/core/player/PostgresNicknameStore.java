package com.omegarealm.core.player;

import com.omegarealm.core.authorization.Role;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static com.omegarealm.core.player.NicknameChangeResult.Status;
import static com.omegarealm.core.player.PostgresPlayerSessionRepository.prepare;

/**
 * SQL within the session owner's transaction; never commits or closes the borrowed connection.
 * All name writers take the namespace lock before touching player/profile rows. This small,
 * realm-wide boundary also orders claims against joins from a different server ID.
 */
final class PostgresNicknameStore {

    private final Connection connection;
    private final String serverId;

    PostgresNicknameStore(Connection connection, String serverId) {
        this.connection = connection;
        this.serverId = serverId;
    }

    void lockNames() throws SQLException {
        try (PreparedStatement statement = prepare(connection, "SELECT pg_advisory_xact_lock(1330464584, 0)")) {
            statement.execute();
        }
    }

    Optional<PlayerNickname> load(UUID playerUuid) throws SQLException {
        try (PreparedStatement statement = prepare(connection, """
                SELECT p.uuid, n.nickname, n.revision, a.role
                FROM players p LEFT JOIN player_nicknames n ON n.player_uuid = p.uuid
                LEFT JOIN player_authorizations a ON a.player_uuid = p.uuid
                WHERE p.uuid = ?
                """)) {
            statement.setObject(1, playerUuid);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                long revision = result.getLong("revision");
                if (result.wasNull() || result.getString("role") == null) {
                    throw new IllegalStateException("Existing profile state is missing.");
                }
                Role.valueOf(result.getString("role"));
                return Optional.of(new PlayerNickname(playerUuid, result.getString("nickname"), revision));
            }
        }
    }

    NicknameChangeResult change(NicknameChange change) throws SQLException {
        lockNames();
        PlayerNickname current = load(change.playerUuid()).orElse(null);
        if (current == null) {
            return new NicknameChangeResult(Status.NOT_FOUND, null);
        }
        if (current.revision() != change.expectedRevision()) {
            return new NicknameChangeResult(Status.STALE, current);
        }

        String requested = change.nickname();
        if (requested != null) {
            if (!NicknamePolicy.valid(requested)) {
                return new NicknameChangeResult(Status.INVALID, current);
            }
            if (NicknamePolicy.reserved(requested) || accountReserved(requested, change.playerUuid())) {
                return new NicknameChangeResult(Status.RESERVED, current);
            }
            if (nicknameTaken(requested, change.playerUuid())) {
                return new NicknameChangeResult(Status.TAKEN, current);
            }
        }
        if (Objects.equals(current.nickname(), requested)) {
            return new NicknameChangeResult(Status.UNCHANGED, current);
        }

        PlayerNickname updated = replace(current, requested);
        audit(current, updated, requested == null ? "CLEAR" : "SET", change.playerUuid());
        return new NicknameChangeResult(Status.CHANGED, updated);
    }

    private boolean accountReserved(String nickname, UUID owner) throws SQLException {
        try (PreparedStatement statement = prepare(connection, """
                SELECT EXISTS (SELECT 1 FROM player_account_names WHERE name_key = ? AND player_uuid <> ?)
                """)) {
            statement.setString(1, NicknamePolicy.key(nickname));
            statement.setObject(2, owner);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private boolean nicknameTaken(String nickname, UUID owner) throws SQLException {
        try (PreparedStatement statement = prepare(connection, """
                SELECT EXISTS (SELECT 1 FROM player_nicknames
                    WHERE lower(nickname COLLATE "C") = ? AND player_uuid <> ?)
                """)) {
            statement.setString(1, NicknamePolicy.key(nickname));
            statement.setObject(2, owner);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    /**
     * Caller already holds the namespace lock and has initialized the joining player.
     */
    void reserveAccount(UUID joiningUuid, String username) throws SQLException {
        load(joiningUuid).orElseThrow();
        for (String key : NicknamePolicy.accountKeys(username).stream().sorted().toList()) {
            try (PreparedStatement statement = prepare(connection, """
                    INSERT INTO player_account_names (name_key, player_uuid) VALUES (?, ?)
                    ON CONFLICT DO NOTHING
                    """)) {
                statement.setString(1, key);
                statement.setObject(2, joiningUuid);
                statement.executeUpdate();
            }

            PlayerNickname conflict = null;
            try (PreparedStatement statement = prepare(connection, """
                    SELECT player_uuid, nickname, revision FROM player_nicknames
                    WHERE lower(nickname COLLATE "C") = ? AND player_uuid <> ?
                    """)) {
                statement.setString(1, key);
                statement.setObject(2, joiningUuid);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        conflict = new PlayerNickname(result.getObject("player_uuid", UUID.class),
                                result.getString("nickname"), result.getLong("revision"));
                    }
                }
            }
            if (conflict != null) {
                PlayerNickname cleared = replace(conflict, null);
                audit(conflict, cleared, "ACCOUNT_NAME_CONFLICT", joiningUuid);
            }
        }
    }

    private PlayerNickname replace(PlayerNickname previous, String nickname) throws SQLException {
        try (PreparedStatement statement = prepare(connection, """
                UPDATE player_nicknames SET nickname = ?, revision = revision + 1
                WHERE player_uuid = ? AND revision = ? RETURNING revision
                """)) {
            statement.setString(1, nickname);
            statement.setObject(2, previous.playerUuid());
            statement.setLong(3, previous.revision());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("Profile changed outside the name ownership boundary.");
                }
                return new PlayerNickname(previous.playerUuid(), nickname, result.getLong(1));
            }
        }
    }

    private void audit(PlayerNickname previous, PlayerNickname updated, String cause, UUID actor) throws SQLException {
        try (PreparedStatement statement = prepare(connection, """
                INSERT INTO player_nickname_audit
                    (id, player_uuid, server_id, actor_uuid, cause, previous_nickname, new_nickname, revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, updated.playerUuid());
            statement.setString(3, serverId);
            statement.setObject(4, actor);
            statement.setString(5, cause);
            statement.setString(6, previous.nickname());
            statement.setString(7, updated.nickname());
            statement.setLong(8, updated.revision());
            statement.executeUpdate();
        }
    }

    List<NicknameNotice> notices(UUID owner) throws SQLException {
        List<NicknameNotice> notices = new ArrayList<>();
        try (PreparedStatement statement = prepare(connection, """
                SELECT id, previous_nickname, changed_at FROM player_nickname_audit
                WHERE player_uuid = ? AND cause = 'ACCOUNT_NAME_CONFLICT' AND notification_delivered_at IS NULL
                ORDER BY changed_at, id LIMIT 64
                """)) {
            statement.setObject(1, owner);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    notices.add(new NicknameNotice(result.getObject("id", UUID.class), owner,
                            result.getString("previous_nickname"),
                            result.getObject("changed_at", java.time.OffsetDateTime.class).toInstant()));
                }
            }
        }
        return List.copyOf(notices);
    }

    boolean acknowledge(UUID owner, UUID id) throws SQLException {
        try (PreparedStatement statement = prepare(connection, """
                UPDATE player_nickname_audit SET notification_delivered_at = clock_timestamp()
                WHERE id = ? AND player_uuid = ? AND cause = 'ACCOUNT_NAME_CONFLICT'
                    AND notification_delivered_at IS NULL
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, owner);
            return statement.executeUpdate() == 1;
        }
    }
}
