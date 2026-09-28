package com.omegarealm.core.database;

import com.omegarealm.core.authorization.*;
import com.omegarealm.core.command.CommandAuditEvent;
import com.omegarealm.core.command.CommandAuditEvent.Category;
import com.omegarealm.core.command.CommandAuditEvent.Outcome;
import com.omegarealm.core.config.DatabaseConfig;
import com.omegarealm.core.player.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static com.omegarealm.core.player.PersistenceException.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class PlayerSessionRepositoryIntegrationTest {

    private static final Instant JOINED = Instant.parse("2026-09-26T12:00:00Z");
    @TempDir
    static Path directory;
    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().setPort(0)
                .setDataDirectory(directory.resolve("postgres-data").toFile()).start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    private static void seedLegacy(DatabaseConfig config, Path migrations, boolean conflicting) throws Exception {
        try (Database database = bootstrap(config, "filesystem:" + migrations).initialize();
             Connection admin = admin(config); Statement statement = admin.createStatement()) {
            statement.executeUpdate("INSERT INTO players (uuid, username, first_seen_at, last_seen_at) VALUES ('00000000-0000-0000-0000-000000000001', 'Legacy', now(), now())");
            String left = conflicting ? "NULL" : "now()";
            statement.executeUpdate("INSERT INTO player_sessions (id, player_uuid, server_id, joined_at, left_at) VALUES (gen_random_uuid(), '00000000-0000-0000-0000-000000000001', 'alpha', now(), " + left + ")");
            if (conflicting) {
                statement.executeUpdate("INSERT INTO player_sessions (id, player_uuid, server_id, joined_at) SELECT gen_random_uuid(), player_uuid, server_id, joined_at FROM player_sessions");
            }
        }
    }

    private static SessionStart start(String username, Instant joinedAt) {
        return new SessionStart(UUID.randomUUID(), UUID.randomUUID(), username, joinedAt);
    }

    private static PostgresPlayerSessionRepository ready(Database database, String serverId) throws Exception {
        var repository = PostgresPlayerSessionRepository.acquire(database, serverId);
        try {
            repository.recoverInterrupted(JOINED);
            return repository;
        } catch (Exception failure) {
            repository.close();
            throw failure;
        }
    }

    private static Database database() throws Exception {
        return bootstrap(isolatedConfig(3), "classpath:db/migration").initialize();
    }

    private static RoleChangeRequest consoleChange(UUID uuid, Role role) {
        return new RoleChangeRequest(new AuthorizationActor.LocalConsole(), uuid, role, "Staff appointment");
    }

    private static void assertCommandEvents(Connection admin, RoleChangeRequest request,
                                            String actorKind, String finalOutcome, UUID mutationAuditId)
            throws SQLException {
        try (PreparedStatement query = admin.prepareStatement("""
                SELECT server_id, category, command_path, actor_kind, actor_uuid, actor_role,
                       target_uuid, requested_role, outcome, role_change_audit_id
                FROM command_audit WHERE operation_id = ?
                ORDER BY CASE WHEN outcome = 'QUEUED' THEN 0 ELSE 1 END
                """)) {
            query.setObject(1, request.operationId());
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("QUEUED", rows.getString("outcome"));
                assertEquals(request.role().name(), rows.getString("requested_role"));
                assertNull(rows.getObject("role_change_audit_id"));
                assertEquals(actorKind, rows.getString("actor_kind"));
                assertTrue(rows.next());
                assertEquals(finalOutcome, rows.getString("outcome"));
                assertEquals(request.role().name(), rows.getString("requested_role"));
                assertEquals(mutationAuditId, rows.getObject("role_change_audit_id", UUID.class));
                assertFalse(rows.next());
            }
        }
        try (PreparedStatement query = admin.prepareStatement("""
                SELECT count(*) FROM command_audit
                WHERE operation_id = ? AND server_id = 'alpha' AND category = 'MANAGEMENT'
                  AND command_path = 'role.set' AND target_uuid = ? AND actor_kind = ?
                """)) {
            query.setObject(1, request.operationId());
            query.setObject(2, request.targetUuid());
            query.setString(3, actorKind);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
            }
        }
    }

    private static DatabaseConfig isolatedConfig(int poolSize) throws Exception {
        String name = "omega_player_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = postgres.getPostgresDatabase().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return new DatabaseConfig("127.0.0.1", postgres.getPort(), name, "postgres", "TEST_PASSWORD", poolSize);
    }

    private static Connection admin(DatabaseConfig config) throws SQLException {
        return postgres.getDatabase("postgres", config.database()).getConnection();
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static PostgresBootstrap bootstrap(DatabaseConfig config, String location) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        return new PostgresBootstrap(config, ignored -> "test-only-password", logger,
                PlayerSessionRepositoryIntegrationTest.class.getClassLoader(), location);
    }

    private static void setAuthorization(DatabaseConfig config, UUID uuid, Role role, long revision) throws Exception {
        // Fixture-only writes: no unrestricted role mutation API is exposed by the application.
        try (Connection admin = admin(config); PreparedStatement statement = admin.prepareStatement(
                "UPDATE player_authorizations SET role = ?, revision = ? WHERE player_uuid = ?")) {
            statement.setString(1, role.name());
            statement.setLong(2, revision);
            statement.setObject(3, uuid);
            assertEquals(1, statement.executeUpdate());
        }
    }

    @Test
    void significantNativeCommandsUseTheSameDurableTableWithoutMutationRows() throws Exception {
        try (Database database = database(); var repository = ready(database, "alpha");
             Connection admin = database.openConnection()) {
            UUID playerId = UUID.randomUUID();
            CommandAuditEvent staff = new CommandAuditEvent(UUID.randomUUID(),
                    new AuthorizationActor.Player(playerId, Role.MODERATOR), Category.STAFF,
                    "moderate.inspect", Outcome.INVOKED, null, null, null);
            CommandAuditEvent management = new CommandAuditEvent(UUID.randomUUID(),
                    new AuthorizationActor.LocalConsole(), Category.MANAGEMENT,
                    "time.set", Outcome.INVOKED, null, null, null);

            repository.appendCommandAudit(staff);
            repository.appendCommandAudit(management);

            try (PreparedStatement query = admin.prepareStatement("""
                    SELECT category, command_path, actor_kind, actor_uuid, outcome,
                           target_uuid, requested_role, role_change_audit_id
                    FROM command_audit WHERE operation_id = ?
                    """)) {
                query.setObject(1, staff.operationId());
                try (ResultSet row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals("STAFF", row.getString("category"));
                    assertEquals("moderate.inspect", row.getString("command_path"));
                    assertEquals("PLAYER", row.getString("actor_kind"));
                    assertEquals(playerId, row.getObject("actor_uuid", UUID.class));
                    assertEquals("INVOKED", row.getString("outcome"));
                    assertNull(row.getObject("target_uuid"));
                    assertNull(row.getObject("requested_role"));
                    assertNull(row.getObject("role_change_audit_id"));
                    assertFalse(row.next());
                }
                query.setObject(1, management.operationId());
                try (ResultSet row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals("MANAGEMENT", row.getString("category"));
                    assertEquals("time.set", row.getString("command_path"));
                    assertEquals("LOCAL_CONSOLE", row.getString("actor_kind"));
                    assertEquals("INVOKED", row.getString("outcome"));
                    assertFalse(row.next());
                }
            }
            assertEquals(0, scalar(admin, "SELECT count(*) FROM role_change_audit"));
        }
    }

    @Test
    void roleChangeCommitsAuditAndRealmWideRevisionWhileNoOpDoesNotWrite() throws Exception {
        try (Database database = database();
             var alpha = ready(database, "alpha"); var beta = ready(database, "beta");
             Connection admin = database.openConnection()) {
            SessionStart target = start("Target", JOINED);
            alpha.join(target);
            alpha.endSession(target.sessionId(), JOINED.plusSeconds(1), SessionEnd.QUIT);
            var changed = alpha.changeRole(consoleChange(target.playerUuid(), Role.OWNER));
            assertEquals(RoleChangeResult.Status.CHANGED, changed.status());
            assertEquals(1, changed.authorization().revision());
            assertEquals(changed.authorization(), beta.loadAuthorizations(List.of(target.playerUuid())).get(target.playerUuid()));
            try (PreparedStatement query = admin.prepareStatement("SELECT * FROM role_change_audit WHERE id = ?")) {
                query.setObject(1, changed.auditId());
                try (ResultSet row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals("alpha", row.getString("server_id"));
                    assertEquals("LOCAL_CONSOLE", row.getString("actor_kind"));
                    assertNull(row.getObject("actor_uuid"));
                    assertEquals("PLAYER", row.getString("previous_role"));
                    assertEquals("OWNER", row.getString("new_role"));
                    assertEquals(0, row.getLong("previous_revision"));
                    assertEquals(1, row.getLong("new_revision"));
                    assertEquals("Staff appointment", row.getString("reason"));
                    assertNotNull(row.getObject("changed_at"));
                    assertFalse(row.next());
                }
            }
            var unchanged = alpha.changeRole(consoleChange(target.playerUuid(), Role.OWNER));
            assertEquals(RoleChangeResult.Status.UNCHANGED, unchanged.status());
            assertEquals(1, unchanged.authorization().revision());
            assertNull(unchanged.auditId());
            assertEquals(RoleChangeResult.Status.NOT_FOUND,
                    alpha.changeRole(consoleChange(UUID.randomUUID(), Role.ADMIN)).status());
            assertEquals(RoleChangeResult.Status.DENIED, alpha.changeRole(new RoleChangeRequest(
                    new AuthorizationActor.Untrusted(), target.playerUuid(), Role.PLAYER, "Denied request")).status());
            assertEquals(1, scalar(admin, "SELECT count(*) FROM role_change_audit"));
        }
    }

    @Test
    void acceptedRoleRequestsWritePairedCommandEventsWithoutCopyingReasons() throws Exception {
        try (Database database = database(); var repository = ready(database, "alpha");
             Connection admin = database.openConnection()) {
            SessionStart target = start("Target", JOINED);
            repository.join(target);

            RoleChangeRequest changedRequest = consoleChange(target.playerUuid(), Role.ADMIN);
            RoleChangeResult changed = repository.changeRole(changedRequest);
            assertEquals(RoleChangeResult.Status.CHANGED, changed.status());
            assertCommandEvents(admin, changedRequest, "LOCAL_CONSOLE", "CHANGED", changed.auditId());

            RoleChangeRequest unchangedRequest = consoleChange(target.playerUuid(), Role.ADMIN);
            assertEquals(RoleChangeResult.Status.UNCHANGED, repository.changeRole(unchangedRequest).status());
            assertCommandEvents(admin, unchangedRequest, "LOCAL_CONSOLE", "UNCHANGED", null);

            RoleChangeRequest deniedRequest = new RoleChangeRequest(new AuthorizationActor.Untrusted(),
                    target.playerUuid(), Role.PLAYER, "Denied request");
            assertEquals(RoleChangeResult.Status.DENIED, repository.changeRole(deniedRequest).status());
            assertCommandEvents(admin, deniedRequest, "UNTRUSTED", "DENIED", null);

            RoleChangeRequest missingRequest = consoleChange(UUID.randomUUID(), Role.MODERATOR);
            assertEquals(RoleChangeResult.Status.NOT_FOUND, repository.changeRole(missingRequest).status());
            assertCommandEvents(admin, missingRequest, "LOCAL_CONSOLE", "NOT_FOUND", null);

            assertEquals(8, scalar(admin, "SELECT count(*) FROM command_audit"));
            assertEquals(1, scalar(admin, "SELECT count(*) FROM role_change_audit"));
            assertEquals(0, scalar(admin, """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_name = 'command_audit' AND column_name IN ('reason', 'arguments', 'raw_command')
                    """));
        }
    }

    @Test
    void commandAuditAcceptanceFailurePreventsRoleMutation() throws Exception {
        try (Database database = database(); var repository = ready(database, "alpha");
             Connection admin = database.openConnection(); Statement sql = admin.createStatement()) {
            SessionStart target = start("Target", JOINED);
            repository.join(target);
            sql.execute("ALTER TABLE command_audit ADD CONSTRAINT reject_queued CHECK (outcome <> 'QUEUED') NOT VALID");

            PersistenceException failure = assertThrows(PersistenceException.class, () -> repository.changeRole(
                    new RoleChangeRequest(new AuthorizationActor.LocalConsole(), target.playerUuid(),
                            Role.ADMIN, "DO_NOT_LOG_REASON")));
            assertEquals(DATABASE_FAILURE, failure.kind());
            assertFalse(failure.getMessage().contains("DO_NOT_LOG"));
            assertNull(failure.getCause());
            assertEquals(0, scalar(admin, "SELECT count(*) FROM command_audit"));
            assertEquals(0, scalar(admin, "SELECT count(*) FROM role_change_audit"));
            assertEquals(1, scalar(admin, "SELECT count(*) FROM player_authorizations WHERE role='PLAYER' AND revision=0"));
        }
    }

    @Test
    void commandAuditCompletionFailureRollsBackRoleMutationAndLeavesQueuedEvent() throws Exception {
        try (Database database = database(); var repository = ready(database, "alpha");
             Connection admin = database.openConnection(); Statement sql = admin.createStatement()) {
            SessionStart target = start("Target", JOINED);
            repository.join(target);
            sql.execute("ALTER TABLE command_audit ADD CONSTRAINT reject_changed CHECK (outcome <> 'CHANGED') NOT VALID");

            PersistenceException failure = assertThrows(PersistenceException.class, () -> repository.changeRole(
                    new RoleChangeRequest(new AuthorizationActor.LocalConsole(), target.playerUuid(),
                            Role.ADMIN, "DO_NOT_LOG_REASON")));
            assertEquals(DATABASE_FAILURE, failure.kind());
            assertFalse(failure.getMessage().contains("DO_NOT_LOG"));
            assertNull(failure.getCause());
            assertEquals(1, scalar(admin, "SELECT count(*) FROM command_audit WHERE outcome='QUEUED'"));
            assertEquals(0, scalar(admin, "SELECT count(*) FROM role_change_audit"));
            assertEquals(1, scalar(admin, "SELECT count(*) FROM player_authorizations WHERE role='PLAYER' AND revision=0"));
        }
    }

    @Test
    void roleWriterRechecksStoredActorAuthorityAndProtectsPeers() throws Exception {
        try (Database database = database(); var repository = ready(database, "alpha");
             Connection admin = database.openConnection()) {
            SessionStart actor = start("Actor", JOINED);
            SessionStart target = start("Target", JOINED);
            repository.join(actor);
            repository.join(target);
            var forgedOwner = new AuthorizationActor.Player(actor.playerUuid(), Role.OWNER);
            assertEquals(RoleChangeResult.Status.DENIED, repository.changeRole(new RoleChangeRequest(
                    forgedOwner, target.playerUuid(), Role.ADMIN, "Forged authority")).status());
            repository.changeRole(consoleChange(actor.playerUuid(), Role.ADMIN));
            var claimedPlayer = new AuthorizationActor.Player(actor.playerUuid(), Role.PLAYER);
            var promotionRequest = new RoleChangeRequest(
                    claimedPlayer, target.playerUuid(), Role.MODERATOR, "Moderator appointment");
            var promotion = repository.changeRole(promotionRequest);
            assertEquals(RoleChangeResult.Status.CHANGED, promotion.status());
            assertEquals(1, scalar(admin, "SELECT count(*) FROM role_change_audit WHERE actor_role='ADMIN' AND actor_kind='PLAYER'"));
            try (PreparedStatement query = admin.prepareStatement("""
                    SELECT outcome, actor_role FROM command_audit WHERE operation_id = ?
                    ORDER BY CASE WHEN outcome = 'QUEUED' THEN 0 ELSE 1 END
                    """)) {
                query.setObject(1, promotionRequest.operationId());
                try (ResultSet rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("QUEUED", rows.getString("outcome"));
                    assertEquals("PLAYER", rows.getString("actor_role"));
                    assertTrue(rows.next());
                    assertEquals("CHANGED", rows.getString("outcome"));
                    assertEquals("ADMIN", rows.getString("actor_role"));
                    assertFalse(rows.next());
                }
            }
            repository.changeRole(consoleChange(target.playerUuid(), Role.ADMIN));
            assertEquals(RoleChangeResult.Status.DENIED, repository.changeRole(new RoleChangeRequest(
                    forgedOwner, target.playerUuid(), Role.PLAYER, "Peer demotion")).status());
            assertEquals(RoleChangeResult.Status.DENIED, repository.changeRole(new RoleChangeRequest(
                    forgedOwner, actor.playerUuid(), Role.PLAYER, "Self demotion")).status());
            assertEquals(3, scalar(admin, "SELECT count(*) FROM role_change_audit"));
        }
    }

    @Test
    void failedAuditInsertionRollsBackAuthorityAndDoesNotLeakReason() throws Exception {
        try (Database database = database(); var repository = ready(database, "alpha");
             Connection admin = database.openConnection(); Statement sql = admin.createStatement()) {
            SessionStart target = start("Target", JOINED);
            repository.join(target);
            sql.execute("ALTER TABLE role_change_audit ADD CONSTRAINT reject_audit CHECK (false) NOT VALID");
            PersistenceException failure = assertThrows(PersistenceException.class, () -> repository.changeRole(
                    new RoleChangeRequest(new AuthorizationActor.LocalConsole(), target.playerUuid(), Role.ADMIN, "DO_NOT_LOG_REASON")));
            assertEquals(DATABASE_FAILURE, failure.kind());
            assertFalse(failure.getMessage().contains("DO_NOT_LOG"));
            assertNull(failure.getCause());
            assertEquals(1, scalar(admin, "SELECT count(*) FROM player_authorizations WHERE role='PLAYER' AND revision=0"));
            assertEquals(0, scalar(admin, "SELECT count(*) FROM role_change_audit"));
        }
    }

    @Test
    void concurrentServersSerializeRoleChangesWithoutLosingAuditOrRevision() throws Exception {
        try (Database database = database()) {
            UUID target;
            try (var seed = ready(database, "seed")) {
                SessionStart start = start("Target", JOINED);
                seed.join(start);
                seed.endSession(start.sessionId(), JOINED.plusSeconds(1), SessionEnd.QUIT);
                target = start.playerUuid();
            }
            ExecutorService writers = Executors.newFixedThreadPool(2);
            CountDownLatch acquired = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            try {
                Future<RoleChangeResult> first = writers.submit(() -> {
                    try (var repository = ready(database, "alpha")) {
                        acquired.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        return repository.changeRole(consoleChange(target, Role.MODERATOR));
                    }
                });
                Future<RoleChangeResult> second = writers.submit(() -> {
                    try (var repository = ready(database, "beta")) {
                        acquired.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        return repository.changeRole(consoleChange(target, Role.ADMIN));
                    }
                });
                assertTrue(acquired.await(5, TimeUnit.SECONDS));
                release.countDown();
                assertEquals(RoleChangeResult.Status.CHANGED, first.get(10, TimeUnit.SECONDS).status());
                assertEquals(RoleChangeResult.Status.CHANGED, second.get(10, TimeUnit.SECONDS).status());
                try (Connection admin = database.openConnection()) {
                    assertEquals(2, scalar(admin, "SELECT revision FROM player_authorizations"));
                    assertEquals(2, scalar(admin, "SELECT count(*) FROM role_change_audit"));
                    assertEquals(3, scalar(admin, "SELECT sum(new_revision) FROM role_change_audit"));
                }
            } finally {
                release.countDown();
                writers.shutdownNow();
                assertTrue(writers.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void rolesPersistAcrossOwnersAndRestartWithoutReconnectResettingThem() throws Exception {
        DatabaseConfig config = isolatedConfig(3);
        SessionStart first = start("Player", JOINED);
        try (Database database = bootstrap(config, "classpath:db/migration").initialize();
             var alpha = ready(database, "alpha"); var beta = ready(database, "beta")) {
            assertEquals(AuthorizationSnapshot.initial(first.playerUuid()),
                    alpha.loadPlayer(first.playerUuid()).authorization());
            alpha.join(first);
            alpha.endSession(first.sessionId(), JOINED.plusSeconds(1), SessionEnd.QUIT);

            long revision = 1;
            for (Role role : Role.values()) {
                setAuthorization(config, first.playerUuid(), role, revision);
                AuthorizationSnapshot expected = new AuthorizationSnapshot(first.playerUuid(), role, revision);
                assertEquals(expected, alpha.loadPlayer(first.playerUuid()).authorization());
                assertEquals(expected, beta.loadPlayer(first.playerUuid()).authorization());
                revision++;
            }

            SessionStart returning = new SessionStart(UUID.randomUUID(), first.playerUuid(),
                    "Renamed", JOINED.plusSeconds(2));
            beta.join(returning);
            beta.join(returning);
            assertEquals(Role.OWNER, beta.loadPlayer(first.playerUuid()).authorization().role());
            assertEquals(4, beta.loadPlayer(first.playerUuid()).authorization().revision());
            beta.endSession(returning.sessionId(), JOINED.plusSeconds(3), SessionEnd.QUIT);
            setAuthorization(config, first.playerUuid(), Role.PLAYER, 100);
        }

        try (Database database = bootstrap(config, "classpath:db/migration").initialize();
             var repository = ready(database, "alpha")) {
            LoadedPlayer loaded = repository.loadPlayer(first.playerUuid());
            assertEquals("Renamed", loaded.storedProfile().orElseThrow().username());
            assertEquals(new AuthorizationSnapshot(first.playerUuid(), Role.PLAYER, 100), loaded.authorization());
        }
    }

    @Test
    void authorizationMigrationBackfillsExistingPlayersWithoutChangingHistory() throws Exception {
        Path previous = Files.createDirectory(directory.resolve("previous-" + UUID.randomUUID()));
        for (String name : new String[]{"V001__create_players.sql", "V002__create_player_sessions.sql",
                "V003__player_session_lifecycle.sql"}) {
            try (var source = getClass().getResourceAsStream("/db/migration/" + name)) {
                assertNotNull(source);
                Files.copy(source, previous.resolve(name));
            }
        }
        DatabaseConfig config = isolatedConfig(3);
        UUID uuid = UUID.randomUUID();
        try (Database database = bootstrap(config, "filesystem:" + previous).initialize();
             Connection admin = admin(config); PreparedStatement statement = admin.prepareStatement("""
                INSERT INTO players (uuid, username, first_seen_at, last_seen_at)
                VALUES (?, 'Legacy', '2026-09-26T12:00:00Z', '2026-09-26T12:00:00Z')
                """)) {
            statement.setObject(1, uuid);
            statement.executeUpdate();
        }

        try (Database database = bootstrap(config, "classpath:db/migration").initialize();
             var repository = ready(database, "alpha"); Connection admin = admin(config)) {
            LoadedPlayer loaded = repository.loadPlayer(uuid);
            assertEquals(AuthorizationSnapshot.initial(uuid), loaded.authorization());
            assertEquals(JOINED, loaded.storedProfile().orElseThrow().firstSeenAt());
            assertEquals(JOINED, loaded.storedProfile().orElseThrow().lastSeenAt());
            assertEquals(0, scalar(admin, "SELECT count(*) FROM player_sessions"));
            assertEquals(6, scalar(admin, "SELECT count(*) FROM flyway_schema_history WHERE success"));
        }
    }

    @Test
    void authorizationSchemaRejectsUnknownRolesNegativeRevisionsDuplicatesAndOrphans() throws Exception {
        DatabaseConfig config = isolatedConfig(3);
        try (Database database = bootstrap(config, "classpath:db/migration").initialize();
             var repository = ready(database, "alpha"); Connection admin = admin(config);
             Statement statement = admin.createStatement()) {
            SessionStart first = start("Player", JOINED);
            repository.join(first);
            assertEquals("23514", assertThrows(SQLException.class,
                    () -> statement.executeUpdate("UPDATE player_authorizations SET role = 'INVALID'")).getSQLState());
            assertEquals("23514", assertThrows(SQLException.class,
                    () -> statement.executeUpdate("UPDATE player_authorizations SET revision = -1")).getSQLState());
            assertEquals("23505", assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO player_authorizations SELECT player_uuid, role, revision FROM player_authorizations
                    """)).getSQLState());
            assertEquals("23503", assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO player_authorizations VALUES (gen_random_uuid(), 'ADMIN', 1)
                    """)).getSQLState());
            assertEquals(AuthorizationSnapshot.initial(first.playerUuid()),
                    repository.loadPlayer(first.playerUuid()).authorization());
        }
    }

    @Test
    void preparationReadsAConsistentProfileAndAuthorizationView() throws Exception {
        DatabaseConfig config = isolatedConfig(3);
        try (Database database = bootstrap(config, "classpath:db/migration").initialize();
             var repository = ready(database, "alpha"); Connection changing = admin(config)) {
            SessionStart first = start("Original", JOINED);
            repository.join(first);
            changing.setAutoCommit(false);
            try (Statement statement = changing.createStatement()) {
                statement.executeUpdate("UPDATE players SET username = 'Changed'");
                statement.executeUpdate("UPDATE player_authorizations SET role = 'ADMIN', revision = 9");
            }

            LoadedPlayer before = repository.loadPlayer(first.playerUuid());
            assertEquals("Original", before.storedProfile().orElseThrow().username());
            assertEquals(AuthorizationSnapshot.initial(first.playerUuid()), before.authorization());
            changing.commit();
            LoadedPlayer after = repository.loadPlayer(first.playerUuid());
            assertEquals("Changed", after.storedProfile().orElseThrow().username());
            assertEquals(new AuthorizationSnapshot(first.playerUuid(), Role.ADMIN, 9), after.authorization());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "invalid-role", "negative-revision"})
    void corruptExistingAuthorizationFailsClosedWithoutDefaultingOrLeaking(String corruption) throws Exception {
        DatabaseConfig config = isolatedConfig(3);
        try (Database database = bootstrap(config, "classpath:db/migration").initialize();
             var repository = ready(database, "alpha")) {
            SessionStart first = start("Player", JOINED);
            repository.join(first);
            try (Connection admin = admin(config); Statement statement = admin.createStatement()) {
                switch (corruption) {
                    case "missing" -> statement.executeUpdate("DELETE FROM player_authorizations");
                    case "invalid-role" -> {
                        statement.execute("ALTER TABLE player_authorizations DROP CONSTRAINT player_authorization_role");
                        statement.executeUpdate("UPDATE player_authorizations SET role = 'DO_NOT_LOG'");
                    }
                    case "negative-revision" -> {
                        statement.execute("ALTER TABLE player_authorizations DROP CONSTRAINT player_authorization_revision");
                        statement.executeUpdate("UPDATE player_authorizations SET revision = -1");
                    }
                    default -> throw new AssertionError();
                }
            }
            PersistenceException failure = assertThrows(PersistenceException.class,
                    () -> repository.loadPlayer(first.playerUuid()));
            assertEquals(DATABASE_FAILURE, failure.kind());
            assertFalse(failure.getMessage().contains("DO_NOT_LOG"));
            assertNull(failure.getCause());
            assertEquals(OWNERSHIP_LOST, assertThrows(PersistenceException.class, repository::checkOwnership).kind());
        }
    }

    @Test
    void authorizationInsertFailureRollsBackPlayerAndSessionTogether() throws Exception {
        DatabaseConfig config = isolatedConfig(3);
        try (Database database = bootstrap(config, "classpath:db/migration").initialize();
             var repository = ready(database, "alpha")) {
            try (Connection admin = admin(config); Statement statement = admin.createStatement()) {
                statement.execute("""
                        CREATE FUNCTION reject_authorization() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN RAISE EXCEPTION 'DO_NOT_LOG_THIS_SECRET'; END $$
                        """);
                statement.execute("""
                        CREATE TRIGGER reject_authorization BEFORE INSERT ON player_authorizations
                        FOR EACH ROW EXECUTE FUNCTION reject_authorization()
                        """);
            }
            PersistenceException failure = assertThrows(PersistenceException.class,
                    () -> repository.join(start("Player", JOINED)));
            assertFalse(failure.getMessage().contains("DO_NOT_LOG"));
            assertNull(failure.getCause());
            try (Connection admin = admin(config)) {
                assertEquals(0, scalar(admin, "SELECT count(*) FROM players"));
                assertEquals(0, scalar(admin, "SELECT count(*) FROM player_authorizations"));
                assertEquals(0, scalar(admin, "SELECT count(*) FROM player_sessions"));
            }
        }
    }

    @Test
    void preparationReadsDoNotCreatePlayersOrSessions() throws Exception {
        DatabaseConfig config = isolatedConfig(2);
        try (Database database = bootstrap(config, "classpath:db/migration").initialize();
             var repository = ready(database, "alpha")) {
            UUID newPlayer = UUID.randomUUID();
            LoadedPlayer loaded = repository.loadPlayer(newPlayer);
            assertTrue(loaded.storedProfile().isEmpty());
            assertEquals(AuthorizationSnapshot.initial(newPlayer), loaded.authorization());
            assertTrue(repository.findSession(UUID.randomUUID()).isEmpty());
            try (Connection admin = admin(config)) {
                assertEquals(0, scalar(admin, "SELECT count(*) FROM players"));
                assertEquals(0, scalar(admin, "SELECT count(*) FROM player_sessions"));
                assertEquals(0, scalar(admin, "SELECT count(*) FROM player_authorizations"));
            }
        }
    }

    @Test
    void firstJoinReturningJoinAndRenamePreserveIdentity() throws Exception {
        try (Database database = database(); var repository = ready(database, "alpha")) {
            SessionStart first = start("Original", JOINED);
            PlayerProfile created = repository.join(first);
            assertEquals(first.playerUuid(), created.uuid());
            assertEquals(JOINED, created.firstSeenAt());
            assertEquals(JOINED, created.lastSeenAt());
            assertEquals(created, repository.findPlayer(first.playerUuid()).orElseThrow());

            Instant left = JOINED.plusSeconds(60);
            assertTrue(repository.endSession(first.sessionId(), left, SessionEnd.QUIT));
            SessionStart returning = new SessionStart(UUID.randomUUID(), first.playerUuid(),
                    "Renamed", JOINED.plusSeconds(120));
            PlayerProfile updated = repository.join(returning);
            assertEquals(created.uuid(), updated.uuid());
            assertEquals(created.firstSeenAt(), updated.firstSeenAt());
            assertEquals("Renamed", updated.username());
            assertEquals(returning.joinedAt(), updated.lastSeenAt());

            PlayerSession originalSession = repository.findSession(first.sessionId()).orElseThrow();
            assertEquals(SessionEnd.QUIT, originalSession.endReason());
            assertEquals(left, originalSession.leftAt());
            assertNull(originalSession.recoveredAt());
            assertNull(repository.findSession(returning.sessionId()).orElseThrow().endReason());
        }
    }

    @Test
    void duplicateJoinTokenIsIdempotentButConflictingInputsAreRejected() throws Exception {
        try (Database database = database(); var repository = ready(database, "alpha")) {
            SessionStart start = start("Player", JOINED.plusNanos(123456789));
            PlayerProfile first = repository.join(start);
            assertEquals(first, repository.join(start));
            assertEquals(start.joinedAt(), repository.findSession(start.sessionId()).orElseThrow().joinedAt());

            SessionStart collision = new SessionStart(start.sessionId(), UUID.randomUUID(), "Other", JOINED);
            assertEquals(SESSION_CONFLICT, assertThrows(PersistenceException.class,
                    () -> repository.join(collision)).kind());
            assertTrue(repository.findPlayer(collision.playerUuid()).isEmpty());
        }
    }

    @Test
    void duplicateOpenSessionRollsBackRenameAndLastSeen() throws Exception {
        try (Database database = database(); var repository = ready(database, "alpha")) {
            SessionStart first = start("Original", JOINED);
            PlayerProfile original = repository.join(first);
            SessionStart duplicate = new SessionStart(UUID.randomUUID(), first.playerUuid(),
                    "Uncommitted", JOINED.plusSeconds(60));

            PersistenceException failure = assertThrows(PersistenceException.class,
                    () -> repository.join(duplicate));
            assertEquals(SESSION_CONFLICT, failure.kind());
            assertEquals(original, repository.findPlayer(first.playerUuid()).orElseThrow());
            assertTrue(repository.findSession(duplicate.sessionId()).isEmpty());
            repository.checkOwnership();
        }
    }

    @Test
    void lateQuitCannotCloseReconnectOrMoveLastSeenBackward() throws Exception {
        try (Database database = database(); var repository = ready(database, "alpha")) {
            SessionStart first = start("Player", JOINED);
            repository.join(first);
            Instant left = JOINED.plusSeconds(30);
            assertTrue(repository.endSession(first.sessionId(), left, SessionEnd.QUIT));
            SessionStart reconnect = new SessionStart(UUID.randomUUID(), first.playerUuid(),
                    "Player", JOINED.plusSeconds(60));
            repository.join(reconnect);

            assertFalse(repository.endSession(first.sessionId(), left.plusSeconds(5), SessionEnd.QUIT));
            assertNull(repository.findSession(reconnect.sessionId()).orElseThrow().endReason());
            assertEquals(reconnect.joinedAt(), repository.findPlayer(first.playerUuid()).orElseThrow().lastSeenAt());
            assertEquals(left, repository.findSession(first.sessionId()).orElseThrow().leftAt());
        }
    }

    @Test
    void unknownOrForeignSessionCannotBeClosed() throws Exception {
        try (Database database = database();
             var alpha = ready(database, "alpha"); var beta = ready(database, "beta")) {
            SessionStart start = start("Player", JOINED);
            alpha.join(start);
            assertTrue(beta.findSession(start.sessionId()).isEmpty());
            assertFalse(beta.endSession(start.sessionId(), JOINED.plusSeconds(10), SessionEnd.QUIT));
            assertFalse(alpha.endSession(UUID.randomUUID(), JOINED, SessionEnd.QUIT));
            assertNull(alpha.findSession(start.sessionId()).orElseThrow().endReason());
        }
    }

    @Test
    void invalidLeaveTimeRollsBackAndShutdownClosureIsIdempotent() throws Exception {
        try (Database database = database(); var repository = ready(database, "alpha")) {
            SessionStart start = start("Player", JOINED);
            repository.join(start);
            assertEquals(INVALID_SESSION_END, assertThrows(PersistenceException.class,
                    () -> repository.endSession(start.sessionId(), JOINED.minusSeconds(1), SessionEnd.QUIT)).kind());
            assertNull(repository.findSession(start.sessionId()).orElseThrow().leftAt());
            assertEquals(JOINED, repository.findPlayer(start.playerUuid()).orElseThrow().lastSeenAt());

            assertTrue(repository.endSession(start.sessionId(), JOINED.plusSeconds(10), SessionEnd.SHUTDOWN));
            assertFalse(repository.endSession(start.sessionId(), JOINED.plusSeconds(20), SessionEnd.QUIT));
            assertEquals(SessionEnd.SHUTDOWN, repository.findSession(start.sessionId()).orElseThrow().endReason());
            assertThrows(IllegalArgumentException.class,
                    () -> repository.endSession(start.sessionId(), JOINED, SessionEnd.INTERRUPTED));
        }
    }

    @Test
    void independentServersMayHaveSessionsForTheSameIdentity() throws Exception {
        try (Database database = database();
             var alpha = ready(database, "alpha"); var beta = ready(database, "beta")) {
            SessionStart first = start("Player", JOINED);
            alpha.join(first);
            SessionStart second = new SessionStart(UUID.randomUUID(), first.playerUuid(), "Player", JOINED.plusSeconds(30));
            beta.join(second);
            alpha.endSession(first.sessionId(), JOINED.plusSeconds(10), SessionEnd.QUIT);
            assertEquals(second.joinedAt(), beta.findPlayer(first.playerUuid()).orElseThrow().lastSeenAt());
            assertNull(beta.findSession(second.sessionId()).orElseThrow().endReason());
        }
    }

    @Test
    void recoveryOnlyMarksThisServersAbandonedSessionsWithoutInventingPlaytime() throws Exception {
        try (Database database = database(); var beta = ready(database, "beta")) {
            SessionStart abandoned = start("Player", JOINED);
            try (var alpha = ready(database, "alpha")) {
                alpha.join(abandoned);
            }
            SessionStart other = start("Other", JOINED);
            beta.join(other);

            Instant recovered = JOINED.plusSeconds(3600);
            try (var alpha = PostgresPlayerSessionRepository.acquire(database, "alpha")) {
                assertEquals(1, alpha.recoverInterrupted(recovered));
                assertEquals(0, alpha.recoverInterrupted(recovered.plusSeconds(1)));
                PlayerSession session = alpha.findSession(abandoned.sessionId()).orElseThrow();
                assertEquals(SessionEnd.INTERRUPTED, session.endReason());
                assertNull(session.leftAt());
                assertEquals(recovered, session.recoveredAt());
                assertEquals(JOINED, alpha.findPlayer(abandoned.playerUuid()).orElseThrow().lastSeenAt());
                assertNull(beta.findSession(other.sessionId()).orElseThrow().endReason());

                alpha.join(new SessionStart(UUID.randomUUID(), abandoned.playerUuid(), "Player", recovered));
                assertThrows(IllegalStateException.class, () -> alpha.recoverInterrupted(recovered));
            }
        }
    }

    @Test
    void playerActivityRequiresSuccessfulStartupRecovery() throws Exception {
        try (Database database = database();
             var repository = PostgresPlayerSessionRepository.acquire(database, "alpha")) {
            SessionStart start = start("Player", JOINED);
            assertThrows(IllegalStateException.class, () -> repository.join(start));
            assertThrows(IllegalStateException.class,
                    () -> repository.endSession(start.sessionId(), JOINED, SessionEnd.QUIT));
            assertEquals(0, repository.recoverInterrupted(JOINED));
            repository.join(start);
        }
    }

    @Test
    void secondOwnerIsRejectedAndNormalCloseReleasesOwnership() throws Exception {
        try (Database database = database()) {
            var first = ready(database, "alpha");
            try {
                PersistenceException rejected = assertThrows(PersistenceException.class,
                        () -> PostgresPlayerSessionRepository.acquire(database, "alpha"));
                assertEquals(SERVER_ALREADY_OWNED, rejected.kind());
                first.checkOwnership();
            } finally {
                first.close();
            }
            first.close();
            assertEquals(OWNERSHIP_LOST, assertThrows(PersistenceException.class, first::checkOwnership).kind());
            try (var replacement = ready(database, "alpha")) {
                replacement.checkOwnership();
            }
        }
    }

    @Test
    void simultaneousAcquisitionHasExactlyOneOwner() throws Exception {
        try (Database database = database()) {
            ExecutorService contenders = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            BlockingQueue<Boolean> outcomes = new LinkedBlockingQueue<>();
            Callable<Void> contender = () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try (var owner = PostgresPlayerSessionRepository.acquire(database, "alpha")) {
                    outcomes.add(true);
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (PersistenceException failure) {
                    assertEquals(SERVER_ALREADY_OWNED, failure.kind());
                    outcomes.add(false);
                }
                return null;
            };
            Future<Void> first = contenders.submit(contender);
            Future<Void> second = contenders.submit(contender);
            try {
                start.countDown();
                Boolean one = outcomes.poll(10, TimeUnit.SECONDS);
                Boolean two = outcomes.poll(10, TimeUnit.SECONDS);
                assertNotNull(one);
                assertNotNull(two);
                assertNotEquals(one, two);
            } finally {
                release.countDown();
                contenders.shutdown();
                assertTrue(contenders.awaitTermination(10, TimeUnit.SECONDS));
            }
            first.get();
            second.get();
        }
    }

    @Test
    void lostBackendCannotWriteAfterReplacementTakesOwnership() throws Exception {
        DatabaseConfig config = isolatedConfig(3);
        try (Database database = bootstrap(config, "classpath:db/migration").initialize()) {
            var obsolete = ready(database, "alpha");
            try {
                try (Connection admin = admin(config); Statement statement = admin.createStatement()) {
                    try (ResultSet result = statement.executeQuery("""
                            SELECT pg_terminate_backend(pid) FROM pg_locks
                            WHERE locktype = 'advisory' AND classid::bigint = 1330464583
                              AND objid::bigint = (SELECT id FROM omega_servers WHERE server_id = 'alpha')
                              AND database = (SELECT oid FROM pg_database WHERE datname = current_database())
                            """)) {
                        assertTrue(result.next());
                        assertTrue(result.getBoolean(1));
                    }
                }
                try (var replacement = ready(database, "alpha")) {
                    SessionStart stale = start("Stale", JOINED);
                    assertThrows(PersistenceException.class, () -> obsolete.join(stale));
                    assertTrue(replacement.findPlayer(stale.playerUuid()).isEmpty());
                    assertTrue(replacement.findSession(stale.sessionId()).isEmpty());
                    assertEquals(OWNERSHIP_LOST, assertThrows(PersistenceException.class,
                            obsolete::checkOwnership).kind());
                    replacement.join(start("Current", JOINED));
                }
            } finally {
                obsolete.close();
            }
        }
    }

    @Test
    void repositoryRejectsUseAndCloseFromAnotherThread() throws Exception {
        try (Database database = database(); var repository = ready(database, "alpha")) {
            ExecutorService otherThread = Executors.newSingleThreadExecutor();
            try {
                otherThread.submit(() -> {
                    assertThrows(IllegalStateException.class, repository::checkOwnership);
                    assertThrows(IllegalStateException.class, repository::close);
                }).get(5, TimeUnit.SECONDS);
            } finally {
                otherThread.shutdown();
                assertTrue(otherThread.awaitTermination(5, TimeUnit.SECONDS));
            }
            repository.checkOwnership();
        }
    }

    @Test
    void sessionInsertFailureRollsBackNewIdentityAndDiagnosticsAreSanitized() throws Exception {
        DatabaseConfig config = isolatedConfig(3);
        try (Database database = bootstrap(config, "classpath:db/migration").initialize();
             var repository = ready(database, "alpha")) {
            try (Connection admin = admin(config); Statement statement = admin.createStatement()) {
                statement.execute("""
                        CREATE FUNCTION reject_session() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN RAISE EXCEPTION 'DO_NOT_LOG_THIS_SECRET'; END $$
                        """);
                statement.execute("CREATE TRIGGER reject_session BEFORE INSERT ON player_sessions FOR EACH ROW EXECUTE FUNCTION reject_session()");
            }
            SessionStart start = start("Player", JOINED);
            PersistenceException failure = assertThrows(PersistenceException.class, () -> repository.join(start));
            assertEquals(DATABASE_FAILURE, failure.kind());
            assertTrue(failure.getMessage().contains("SQLSTATE P0001"));
            assertFalse(failure.getMessage().contains("DO_NOT_LOG"));
            assertNull(failure.getCause());
            try (Connection admin = admin(config)) {
                assertEquals(0, scalar(admin, "SELECT count(*) FROM players"));
                assertEquals(0, scalar(admin, "SELECT count(*) FROM player_authorizations"));
                assertEquals(0, scalar(admin, "SELECT count(*) FROM player_sessions"));
            }
            assertEquals(OWNERSHIP_LOST, assertThrows(PersistenceException.class, repository::checkOwnership).kind());
        }
    }

    @Test
    void closureFailureRollsBackSessionAndProfileTogether() throws Exception {
        DatabaseConfig config = isolatedConfig(3);
        try (Database database = bootstrap(config, "classpath:db/migration").initialize();
             var repository = ready(database, "alpha")) {
            SessionStart start = start("Player", JOINED);
            repository.join(start);
            try (Connection admin = admin(config); Statement statement = admin.createStatement()) {
                statement.execute("""
                        CREATE FUNCTION reject_player_update() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN RAISE EXCEPTION 'DO_NOT_LOG_THIS_SECRET'; END $$
                        """);
                statement.execute("CREATE TRIGGER reject_player_update BEFORE UPDATE ON players FOR EACH ROW EXECUTE FUNCTION reject_player_update()");
            }
            assertThrows(PersistenceException.class,
                    () -> repository.endSession(start.sessionId(), JOINED.plusSeconds(30), SessionEnd.QUIT));
            try (Connection admin = admin(config)) {
                assertEquals(1, scalar(admin, "SELECT count(*) FROM player_sessions WHERE left_at IS NULL AND end_reason IS NULL"));
                try (Statement statement = admin.createStatement();
                     ResultSet result = statement.executeQuery("SELECT last_seen_at FROM players")) {
                    assertTrue(result.next());
                    assertEquals(JOINED, result.getTimestamp(1).toInstant());
                }
            }
        }
    }

    @Test
    void brokenConnectionDiagnosticsDoNotLeakThroughHikariLogging() throws Exception {
        DatabaseConfig config = isolatedConfig(3);
        try (LogCapture capture = new LogCapture();
             Database database = bootstrap(config, "classpath:db/migration").initialize();
             var repository = ready(database, "alpha")) {
            org.slf4j.LoggerFactory.getLogger("com.zaxxer.hikari.pool.ProxyConnection").warn("Capture probe");
            assertTrue(capture.messages.stream().anyMatch(message -> message.contains("Capture probe")));
            capture.messages.clear();

            try (Connection admin = admin(config); Statement statement = admin.createStatement()) {
                statement.execute("""
                        CREATE FUNCTION reject_broken_session() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN RAISE EXCEPTION USING MESSAGE = 'DO_NOT_LOG_THIS_SECRET', ERRCODE = '08006'; END $$
                        """);
                statement.execute("CREATE TRIGGER reject_broken_session BEFORE INSERT ON player_sessions FOR EACH ROW EXECUTE FUNCTION reject_broken_session()");
            }
            PersistenceException failure = assertThrows(PersistenceException.class,
                    () -> repository.join(start("Player", JOINED)));
            assertTrue(failure.getMessage().contains("SQLSTATE 08006"));
            assertNull(failure.getCause());
            repository.close();
            assertTrue(capture.messages.isEmpty(), "Hikari must not forward a raw broken-connection exception");
        }
    }

    @Test
    void interruptionDuringInsertRollsBackAndPreservesTheWorkerInterrupt() throws Exception {
        DatabaseConfig config = isolatedConfig(3);
        try (Database database = bootstrap(config, "classpath:db/migration").initialize()) {
            try (Connection admin = admin(config); Statement statement = admin.createStatement()) {
                statement.execute("""
                        CREATE FUNCTION delay_session() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN PERFORM pg_sleep(2); RETURN NEW; END $$
                        """);
                statement.execute("CREATE TRIGGER delay_session BEFORE INSERT ON player_sessions FOR EACH ROW EXECUTE FUNCTION delay_session()");
            }
            ExecutorService worker = Executors.newSingleThreadExecutor();
            AtomicReference<Thread> ownerThread = new AtomicReference<>();
            CountDownLatch acquired = new CountDownLatch(1);
            Future<Boolean> operation = worker.submit(() -> {
                try (var repository = ready(database, "alpha")) {
                    ownerThread.set(Thread.currentThread());
                    acquired.countDown();
                    PersistenceException failure = assertThrows(PersistenceException.class,
                            () -> repository.join(start("Player", JOINED)));
                    assertEquals(CANCELLED, failure.kind());
                }
                return Thread.currentThread().isInterrupted();
            });
            try {
                assertTrue(acquired.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean inserting = false;
                try (Connection admin = admin(config)) {
                    while (System.nanoTime() < deadline) {
                        if (scalar(admin, "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND application_name = 'OmegaCore' AND wait_event = 'PgSleep'") > 0) {
                            inserting = true;
                            break;
                        }
                        Thread.sleep(10);
                    }
                    assertTrue(inserting, "Interrupt must occur during real session insertion");
                    ownerThread.get().interrupt();
                    assertTrue(operation.get(10, TimeUnit.SECONDS));
                    assertEquals(0, scalar(admin, "SELECT count(*) FROM players"));
                    assertEquals(0, scalar(admin, "SELECT count(*) FROM player_sessions"));
                }
            } finally {
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
            }
            try (var replacement = ready(database, "alpha")) {
                replacement.checkOwnership();
            }
        }
    }

    @Test
    void lockedPlayerWriteTimesOutWithoutCommittingOrLosingCleanup() throws Exception {
        DatabaseConfig config = isolatedConfig(3);
        try (Database database = bootstrap(config, "classpath:db/migration").initialize()) {
            SessionStart first = start("Original", JOINED);
            var repository = ready(database, "alpha");
            try {
                repository.join(first);
                repository.endSession(first.sessionId(), JOINED.plusSeconds(1), SessionEnd.QUIT);
                try (Connection blocker = admin(config); Statement statement = blocker.createStatement()) {
                    blocker.setAutoCommit(false);
                    try (ResultSet result = statement.executeQuery("SELECT uuid FROM players FOR UPDATE")) {
                        assertTrue(result.next());
                    }
                    SessionStart blocked = new SessionStart(UUID.randomUUID(), first.playerUuid(),
                            "Uncommitted", JOINED.plusSeconds(2));
                    long started = System.nanoTime();
                    PersistenceException failure = assertThrows(PersistenceException.class,
                            () -> repository.join(blocked));
                    assertEquals(DATABASE_FAILURE, failure.kind());
                    assertTrue(failure.getMessage().contains("SQLSTATE 57014"));
                    assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10));
                    blocker.rollback();
                }
            } finally {
                repository.close();
            }
            try (var replacement = ready(database, "alpha")) {
                assertEquals("Original", replacement.findPlayer(first.playerUuid()).orElseThrow().username());
                try (Connection admin = admin(config)) {
                    assertEquals(1, scalar(admin, "SELECT count(*) FROM player_sessions"));
                }
            }
        }
    }

    @Test
    void schemaRejectsInvalidEndStatesAndDuplicateActiveSessions() throws Exception {
        DatabaseConfig config = isolatedConfig(3);
        try (Database database = bootstrap(config, "classpath:db/migration").initialize();
             var repository = ready(database, "alpha")) {
            SessionStart start = start("Player", JOINED);
            repository.join(start);
            try (Connection admin = admin(config); Statement statement = admin.createStatement()) {
                assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE player_sessions SET left_at = joined_at"));
                assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE player_sessions SET end_reason = 'QUIT'"));
                assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE player_sessions SET end_reason = 'INTERRUPTED'"));
                assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE player_sessions SET end_reason = 'UNKNOWN'"));
                assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE player_sessions SET recovered_at = now()"));
                assertThrows(SQLException.class, () -> statement.executeUpdate("""
                        INSERT INTO player_sessions (id, player_uuid, server_id, joined_at)
                        SELECT gen_random_uuid(), player_uuid, server_id, joined_at FROM player_sessions
                        """));
            }
        }
    }

    @Test
    void migrationPreservesLegacyHistoryAndRefusesConflictingOpenRows() throws Exception {
        Path oldMigrations = Files.createDirectory(directory.resolve("legacy-" + UUID.randomUUID()));
        for (String name : new String[]{"V001__create_players.sql", "V002__create_player_sessions.sql"}) {
            try (var source = getClass().getResourceAsStream("/db/migration/" + name)) {
                assertNotNull(source);
                Files.writeString(oldMigrations.resolve(name), new String(source.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        DatabaseConfig good = isolatedConfig(2);
        seedLegacy(good, oldMigrations, false);
        try (Database database = bootstrap(good, "classpath:db/migration").initialize();
             Connection admin = admin(good)) {
            assertEquals(1, scalar(admin, "SELECT count(*) FROM player_sessions WHERE end_reason = 'LEGACY' AND left_at IS NOT NULL"));
        }

        DatabaseConfig conflicting = isolatedConfig(2);
        seedLegacy(conflicting, oldMigrations, true);
        assertThrows(com.omegarealm.core.database.DatabaseException.class,
                () -> bootstrap(conflicting, "classpath:db/migration").initialize());
        try (Connection admin = admin(conflicting)) {
            assertEquals(2, scalar(admin, "SELECT count(*) FROM player_sessions"));
            assertEquals(2, scalar(admin, "SELECT count(*) FROM flyway_schema_history WHERE success"));
            assertEquals(0, scalar(admin, "SELECT count(*) FROM information_schema.columns WHERE table_name = 'player_sessions' AND column_name = 'end_reason'"));
        }
    }

    private static final class LogCapture extends AbstractAppender implements AutoCloseable {

        private final org.apache.logging.log4j.core.Logger root;
        private final CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();

        private LogCapture() {
            super("PlayerPersistenceCapture", null, null, true, Property.EMPTY_ARRAY);
            root = (org.apache.logging.log4j.core.Logger) LogManager.getRootLogger();
            start();
            root.addAppender(this);
        }

        @Override
        public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
            if (event.getThrown() != null) {
                messages.add(event.getThrown().toString());
            }
        }

        @Override
        public void close() {
            root.removeAppender(this);
            stop();
        }
    }
}
