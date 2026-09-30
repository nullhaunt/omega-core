package com.omegarealm.core.database;

import com.omegarealm.core.authorization.AuthorizationActor;
import com.omegarealm.core.authorization.Role;
import com.omegarealm.core.authorization.RoleChangeRequest;
import com.omegarealm.core.authorization.RoleChangeResult;
import com.omegarealm.core.config.DatabaseConfig;
import com.omegarealm.core.player.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static com.omegarealm.core.player.NicknameChangeResult.Status.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class NicknamePersistenceIntegrationTest {

    private static final Instant JOINED = Instant.parse("2026-09-29T12:00:00Z");
    @TempDir
    static Path directory;
    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().setPort(0)
                .setDataDirectory(directory.resolve("postgres").toFile()).start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    private static DatabaseConfig config() throws Exception {
        String name = "omega_nickname_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = postgres.getPostgresDatabase().getConnection(); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return new DatabaseConfig("127.0.0.1", postgres.getPort(), name, "postgres", "TEST_PASSWORD", 5);
    }

    private static PostgresBootstrap bootstrap(DatabaseConfig config, String location) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        return new PostgresBootstrap(config, ignored -> "test-only-password", logger,
                NicknamePersistenceIntegrationTest.class.getClassLoader(), location);
    }

    private static PostgresPlayerSessionRepository ready(Database database, String id) throws Exception {
        var repository = PostgresPlayerSessionRepository.acquire(database, id);
        try {
            repository.recoverInterrupted(JOINED);
            return repository;
        } catch (Exception failure) {
            repository.close();
            throw failure;
        }
    }

    private static SessionStart player(String name) {
        return new SessionStart(UUID.randomUUID(), UUID.randomUUID(), name, JOINED);
    }

    private static long count(Connection admin, String sql) throws SQLException {
        try (Statement statement = admin.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static NicknameChangeResult set(PlayerProfileRepository profiles, UUID uuid, String nickname) throws Exception {
        long revision = profiles.loadNickname(uuid).orElseThrow().revision();
        return profiles.changeNickname(new NicknameChange(uuid, revision, nickname));
    }

    private static NicknameChangeResult competingClaim(Database database, String server, UUID uuid,
                                                       String name, CountDownLatch ready, CountDownLatch release) throws Exception {
        try (var repository = ready(database, server)) {
            ready.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return set(repository.profiles(), uuid, name);
        }
    }

    @Test
    void offlineNicknameUsernameAndUuidReturnTheSameStoredSnapshotWithoutWrites() throws Exception {
        try (Database database = bootstrap(config(), "classpath:db/migration").initialize();
             var repository = ready(database, "alpha"); Connection admin = database.openConnection()) {
            SessionStart owner = player("AccountName");
            repository.join(owner);
            set(repository.profiles(), owner.playerUuid(), "Aeron_1");
            repository.endSession(owner.sessionId(), JOINED.plusSeconds(1), SessionEnd.QUIT);
            assertEquals(RoleChangeResult.Status.CHANGED, repository.changeRole(new RoleChangeRequest(
                    new AuthorizationActor.LocalConsole(), owner.playerUuid(), Role.MODERATOR, "Staff appointment")).status());
            long commandAuditsBeforeLookup = count(admin, "SELECT count(*) FROM command_audit");

            PublicPlayerProfile expected = repository.resolveTarget(new PlayerTarget.ByUuid(owner.playerUuid())).profile();
            assertEquals(owner.playerUuid(), expected.identity().uuid());
            assertEquals("AccountName", expected.identity().username());
            assertEquals("Aeron_1", expected.nickname().orElseThrow());
            assertEquals(Role.MODERATOR, expected.role());
            for (String name : List.of("aCcOuNtNaMe", "aERON_1")) {
                assertEquals(PlayerTargetResolution.found(expected), repository.resolveTarget(new PlayerTarget.ByName(name)));
            }
            assertEquals(PlayerTargetResolution.missing(), repository.resolveTarget(new PlayerTarget.ByName("Aeron")));
            assertEquals(PlayerTargetResolution.missing(), repository.resolveTarget(new PlayerTarget.ByUuid(UUID.randomUUID())));
            assertEquals(1, count(admin, "SELECT count(*) FROM player_nickname_audit"));
            assertEquals(1, count(admin, "SELECT count(*) FROM role_change_audit"));
            assertEquals(commandAuditsBeforeLookup, count(admin, "SELECT count(*) FROM command_audit"));
        }
    }

    @Test
    void targetSuggestionsUseLiteralPrefixesAndOnlyCurrentNames() throws Exception {
        try (Database database = bootstrap(config(), "classpath:db/migration").initialize();
             var repository = ready(database, "alpha"); Connection admin = database.openConnection()) {
            SessionStart owner = player("AccountName");
            SessionStart other = player("AB_Account");
            SessionStart bedrock = player(".Aeron");
            repository.join(owner);
            repository.join(other);
            repository.join(bedrock);
            set(repository.profiles(), owner.playerUuid(), "A_One");
            repository.endSession(owner.sessionId(), JOINED.plusSeconds(1), SessionEnd.QUIT);
            assertEquals(List.of("A_One"), repository.suggestTargets("a_"));
            assertEquals(List.of(".Aeron"), repository.suggestTargets(".a"));
            assertEquals(List.of("A_One", "AB_Account", "AccountName"), repository.suggestTargets("a"));

            set(repository.profiles(), owner.playerUuid(), "Comet");
            assertTrue(repository.suggestTargets("a_").isEmpty());
            assertEquals(List.of("Comet"), repository.suggestTargets("COM"));
            set(repository.profiles(), owner.playerUuid(), null);
            assertTrue(repository.suggestTargets("Com").isEmpty());
            assertEquals(3, count(admin, "SELECT count(*) FROM player_nickname_audit"));
            assertEquals(0, count(admin, "SELECT count(*) FROM command_audit"));
            assertThrows(IllegalArgumentException.class, () -> repository.suggestTargets("%"));
        }
    }

    @Test
    void targetSuggestionsAreBoundedAndDeduplicateAsciiCase() throws Exception {
        try (Database database = bootstrap(config(), "classpath:db/migration").initialize();
             var repository = ready(database, "alpha"); Connection admin = database.openConnection()) {
            SessionStart owner = player("Aeron");
            repository.join(owner);
            set(repository.profiles(), owner.playerUuid(), "AERON");
            assertEquals(1, repository.suggestTargets("aer").size());
            try (Statement fixture = admin.createStatement()) {
                fixture.executeUpdate("""
                        INSERT INTO players (uuid, username, first_seen_at, last_seen_at)
                        SELECT gen_random_uuid(), 'Many_' || sequence, now(), now()
                        FROM generate_series(1, 100) AS sequence
                        """);
            }
            assertEquals(64, repository.suggestTargets("Many_").size());
            assertEquals(repository.suggestTargets("Many_"), repository.suggestTargets("many_"));
        }
    }

    @Test
    void nicknameChangesClearsAndReuseReplaceOnlyTheCurrentAlias() throws Exception {
        try (Database database = bootstrap(config(), "classpath:db/migration").initialize();
             var repository = ready(database, "alpha")) {
            SessionStart first = player("First");
            SessionStart second = player("Second");
            repository.join(first);
            repository.join(second);
            set(repository.profiles(), first.playerUuid(), "Aeron");
            PublicPlayerProfile before = repository.resolveTarget(new PlayerTarget.ByName("aeron")).profile();

            set(repository.profiles(), first.playerUuid(), "Comet");
            assertEquals(PlayerTargetResolution.missing(), repository.resolveTarget(new PlayerTarget.ByName("Aeron")));
            assertEquals(first.playerUuid(), repository.resolveTarget(new PlayerTarget.ByName("COMET")).profile().identity().uuid());
            set(repository.profiles(), second.playerUuid(), "AERON");
            assertEquals(second.playerUuid(), repository.resolveTarget(new PlayerTarget.ByName("aeron")).profile().identity().uuid());
            set(repository.profiles(), first.playerUuid(), null);
            assertEquals(PlayerTargetResolution.missing(), repository.resolveTarget(new PlayerTarget.ByName("Comet")));
            assertTrue(repository.resolveTarget(new PlayerTarget.ByUuid(first.playerUuid())).profile().nickname().isEmpty());

            assertEquals(first.playerUuid(), before.identity().uuid());
            assertEquals("Aeron", before.nickname().orElseThrow());
        }
    }

    @Test
    void historicalAndUnprefixedFloodgateReservationsAreNotTargetAliases() throws Exception {
        try (Database database = bootstrap(config(), "classpath:db/migration").initialize();
             var repository = ready(database, "alpha")) {
            SessionStart bedrock = player(".Aeron");
            SessionStart java = player("Other");
            repository.join(bedrock);
            repository.join(java);
            assertEquals(bedrock.playerUuid(), repository.resolveTarget(new PlayerTarget.ByName(".aeron")).profile().identity().uuid());
            assertEquals(PlayerTargetResolution.missing(), repository.resolveTarget(new PlayerTarget.ByName("Aeron")));
            assertEquals(RESERVED, set(repository.profiles(), java.playerUuid(), "Aeron").status());

            repository.endSession(bedrock.sessionId(), JOINED.plusSeconds(1), SessionEnd.QUIT);
            repository.join(new SessionStart(UUID.randomUUID(), bedrock.playerUuid(), ".Renamed", JOINED.plusSeconds(2)));
            assertEquals(PlayerTargetResolution.missing(), repository.resolveTarget(new PlayerTarget.ByName(".Aeron")));
            assertEquals(PlayerTargetResolution.missing(), repository.resolveTarget(new PlayerTarget.ByName("Aeron")));
            assertEquals(RESERVED, set(repository.profiles(), java.playerUuid(), "Aeron").status());
            assertEquals(CHANGED, set(repository.profiles(), bedrock.playerUuid(), "Renamed").status());
            assertEquals(bedrock.playerUuid(), repository.resolveTarget(new PlayerTarget.ByName("Renamed")).profile().identity().uuid());
            assertEquals(".Renamed", repository.resolveTarget(new PlayerTarget.ByName("Renamed")).profile().identity().username());
            assertEquals(java.playerUuid(), repository.resolveTarget(new PlayerTarget.ByName("Other")).profile().identity().uuid());
        }
    }

    @Test
    void matchingCanonicalNameAndNicknameForOneUuidCountAsOneTarget() throws Exception {
        try (Database database = bootstrap(config(), "classpath:db/migration").initialize();
             var repository = ready(database, "alpha")) {
            SessionStart owner = player("Aeron");
            repository.join(owner);
            assertEquals(CHANGED, set(repository.profiles(), owner.playerUuid(), "AERON").status());
            PlayerTargetResolution resolution = repository.resolveTarget(new PlayerTarget.ByName("aeron"));
            assertEquals(PlayerTargetResolution.Status.FOUND, resolution.status());
            assertEquals(owner.playerUuid(), resolution.profile().identity().uuid());
            assertEquals("AERON", resolution.profile().nickname().orElseThrow());
        }
    }

    @Test
    void conflictingCanonicalAndNicknameMatchesNeverPreferOneUuid() throws Exception {
        try (Database database = bootstrap(config(), "classpath:db/migration").initialize();
             var repository = ready(database, "alpha"); Connection admin = database.openConnection()) {
            SessionStart canonical = player("Aeron");
            SessionStart alias = player("Other");
            repository.join(canonical);
            repository.join(alias);
            // Simulate inconsistent imported data; ordinary nickname writes reject this claim.
            try (PreparedStatement fixture = admin.prepareStatement("UPDATE player_nicknames SET nickname = 'aERON' WHERE player_uuid = ?")) {
                fixture.setObject(1, alias.playerUuid());
                fixture.executeUpdate();
            }
            assertEquals(PlayerTargetResolution.ambiguous(), repository.resolveTarget(new PlayerTarget.ByName("Aeron")));
            assertEquals(canonical.playerUuid(), repository.resolveTarget(new PlayerTarget.ByUuid(canonical.playerUuid())).profile().identity().uuid());
            assertEquals(alias.playerUuid(), repository.resolveTarget(new PlayerTarget.ByUuid(alias.playerUuid())).profile().identity().uuid());
            try (PreparedStatement fixture = admin.prepareStatement("DELETE FROM player_authorizations WHERE player_uuid = ?")) {
                fixture.setObject(1, canonical.playerUuid());
                fixture.executeUpdate();
            }
            assertEquals(PlayerTargetResolution.ambiguous(), repository.resolveTarget(new PlayerTarget.ByName("Aeron")));
        }
    }

    @Test
    void duplicateNicknameRowsFailAmbiguouslyIfTheUniqueIndexHasBeenRemoved() throws Exception {
        try (Database database = bootstrap(config(), "classpath:db/migration").initialize();
             var repository = ready(database, "alpha"); Connection admin = database.openConnection()) {
            repository.join(player("First"));
            repository.join(player("Second"));
            repository.join(player("Third"));
            try (Statement fixture = admin.createStatement()) {
                fixture.execute("DROP INDEX player_nickname_key_idx");
                fixture.executeUpdate("UPDATE player_nicknames SET nickname = 'Aeron'");
            }
            assertEquals(PlayerTargetResolution.ambiguous(), repository.resolveTarget(new PlayerTarget.ByName("aeron")));
        }
    }

    @Test
    void resolvedNicknameCannotRedirectARoleWriteAfterAliasReuse() throws Exception {
        try (Database database = bootstrap(config(), "classpath:db/migration").initialize();
             var repository = ready(database, "alpha")) {
            SessionStart original = player("Original");
            SessionStart replacement = player("Replacement");
            repository.join(original);
            repository.join(replacement);
            set(repository.profiles(), original.playerUuid(), "Aeron");
            UUID resolved = repository.resolveTarget(new PlayerTarget.ByName("Aeron")).profile().identity().uuid();

            set(repository.profiles(), original.playerUuid(), "Comet");
            set(repository.profiles(), replacement.playerUuid(), "Aeron");
            assertEquals(RoleChangeResult.Status.CHANGED, repository.changeRole(new RoleChangeRequest(
                    new AuthorizationActor.LocalConsole(), resolved, Role.MODERATOR, "Staff appointment")).status());

            assertEquals(Role.MODERATOR, repository.resolveTarget(new PlayerTarget.ByUuid(original.playerUuid())).profile().role());
            assertEquals(Role.PLAYER, repository.resolveTarget(new PlayerTarget.ByName("Aeron")).profile().role());
            assertEquals(replacement.playerUuid(), repository.resolveTarget(new PlayerTarget.ByName("Aeron")).profile().identity().uuid());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingNickname", "invalidNickname", "negativeRevision", "missingRole"})
    void resolverRejectsIncompleteOrInvalidStoredProfileState(String corruption) throws Exception {
        for (String targetKind : List.of("uuid", "username", "nickname")) {
            try (Database database = bootstrap(config(), "classpath:db/migration").initialize();
                 var repository = ready(database, "alpha"); Connection admin = database.openConnection()) {
                SessionStart owner = player("AccountName");
                repository.join(owner);
                set(repository.profiles(), owner.playerUuid(), "Aeron");
                try (Statement fixture = admin.createStatement()) {
                    switch (corruption) {
                        case "missingNickname" -> fixture.executeUpdate("DELETE FROM player_nicknames");
                        case "invalidNickname" -> {
                            fixture.execute("ALTER TABLE player_nicknames DROP CONSTRAINT nickname_syntax");
                            fixture.executeUpdate("UPDATE player_nicknames SET nickname = 'Admin'");
                        }
                        case "negativeRevision" -> {
                            fixture.execute("ALTER TABLE player_nicknames DROP CONSTRAINT nickname_revision");
                            fixture.executeUpdate("UPDATE player_nicknames SET revision = -1");
                        }
                        case "missingRole" -> fixture.executeUpdate("DELETE FROM player_authorizations");
                        default -> throw new AssertionError("Unknown fixture");
                    }
                }
                PlayerTarget target = switch (targetKind) {
                    case "uuid" -> new PlayerTarget.ByUuid(owner.playerUuid());
                    case "username" -> new PlayerTarget.ByName("AccountName");
                    case "nickname" ->
                            new PlayerTarget.ByName(corruption.equals("invalidNickname") ? "Admin" : "Aeron");
                    default -> throw new AssertionError("Unknown fixture");
                };
                // An alias with no row no longer matches; known UUID/name lookups fail closed.
                if (corruption.equals("missingNickname") && targetKind.equals("nickname")) {
                    assertEquals(PlayerTargetResolution.missing(), repository.resolveTarget(target));
                } else {
                    PersistenceException failure = assertThrows(PersistenceException.class, () -> repository.resolveTarget(target));
                    assertEquals(PersistenceException.Kind.DATABASE_FAILURE, failure.kind());
                    assertNull(failure.getCause());
                    assertFalse(failure.getMessage().contains(owner.playerUuid().toString()));
                    assertEquals(PersistenceException.Kind.OWNERSHIP_LOST, assertThrows(PersistenceException.class,
                            () -> repository.resolveTarget(target)).kind());
                }
            }
        }
    }

    @Test
    void additiveMigrationBackfillsRecordedNamesAndBothFloodgateSpellings() throws Exception {
        DatabaseConfig config = config();
        Path old = Files.createDirectory(directory.resolve("migrations-" + UUID.randomUUID()));
        for (String migration : List.of("V001__create_players.sql", "V002__create_player_sessions.sql",
                "V003__player_session_lifecycle.sql", "V004__player_authorization.sql",
                "V005__role_change_audit.sql", "V006__command_audit.sql", "V007__player_target_lookup.sql")) {
            try (var source = NicknamePersistenceIntegrationTest.class.getResourceAsStream("/db/migration/" + migration)) {
                assertNotNull(source);
                Files.copy(source, old.resolve(migration));
            }
        }
        UUID legacy = UUID.randomUUID();
        try (Database database = bootstrap(config, "filesystem:" + old).initialize();
             Connection admin = database.openConnection()) {
            try (PreparedStatement statement = admin.prepareStatement("""
                    INSERT INTO players (uuid, username, first_seen_at, last_seen_at)
                    VALUES (?, '.Legacy', '2026-09-29T12:00:00Z', '2026-09-29T12:00:00Z')
                    """)) {
                statement.setObject(1, legacy);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = admin.prepareStatement(
                    "INSERT INTO player_authorizations (player_uuid, role, revision) VALUES (?, 'OWNER', 4)")) {
                statement.setObject(1, legacy);
                statement.executeUpdate();
            }
        }
        try (Database database = bootstrap(config, "classpath:db/migration").initialize();
             var repository = ready(database, "alpha"); Connection admin = database.openConnection()) {
            assertEquals(new PlayerNickname(legacy, null, 0), repository.profiles().loadNickname(legacy).orElseThrow());
            assertEquals(JOINED, repository.findPlayer(legacy).orElseThrow().firstSeenAt());
            assertEquals(4, repository.loadPlayer(legacy).authorization().revision());
            assertEquals(2, count(admin, "SELECT count(*) FROM player_account_names"));
            assertEquals(0, count(admin, "SELECT count(*) FROM player_nickname_audit"));
            assertEquals(8, count(admin, "SELECT count(*) FROM flyway_schema_history WHERE success"));
            SessionStart other = player("Other");
            repository.join(other);
            assertEquals(RESERVED, set(repository.profiles(), other.playerUuid(), "lEgAcY").status());
        }
    }

    @Test
    void setClearCaseChangesAndStaffNicknameReuseRemainDurableAndAudited() throws Exception {
        DatabaseConfig config = config();
        SessionStart first = player("First");
        SessionStart second = player("Second");
        try (Database database = bootstrap(config, "classpath:db/migration").initialize()) {
            try (var repository = ready(database, "alpha"); Connection admin = database.openConnection()) {
                repository.join(first);
                repository.join(second);
                try (PreparedStatement statement = admin.prepareStatement(
                        "UPDATE player_authorizations SET role = 'MODERATOR', revision = 1 WHERE player_uuid = ?")) {
                    statement.setObject(1, first.playerUuid());
                    statement.executeUpdate();
                }
                var profiles = repository.profiles();
                assertEquals(CHANGED, set(profiles, first.playerUuid(), "Aeron").status());
                assertEquals(UNCHANGED, set(profiles, first.playerUuid(), "Aeron").status());
                assertEquals(CHANGED, set(profiles, first.playerUuid(), "AERON").status());
                assertEquals(TAKEN, set(profiles, second.playerUuid(), "aeron").status());
                assertEquals(CHANGED, set(profiles, first.playerUuid(), "Comet").status());
                assertEquals(CHANGED, set(profiles, second.playerUuid(), "Aeron").status());
                assertEquals(CHANGED, set(profiles, first.playerUuid(), null).status());
                assertEquals(UNCHANGED, set(profiles, first.playerUuid(), null).status());
                assertEquals(CHANGED, set(profiles, second.playerUuid(), "Comet").status());
                assertEquals(6, count(admin, "SELECT count(*) FROM player_nickname_audit"));
                assertEquals(1, repository.loadPlayer(first.playerUuid()).authorization().revision());
                assertTrue(profiles.pendingNicknameNotices(first.playerUuid()).isEmpty());
            }
            try (var restarted = ready(database, "alpha")) {
                assertEquals(new PlayerNickname(first.playerUuid(), null, 4),
                        restarted.profiles().loadNickname(first.playerUuid()).orElseThrow());
                assertEquals(new PlayerNickname(second.playerUuid(), "Comet", 2),
                        restarted.profiles().loadNickname(second.playerUuid()).orElseThrow());
            }
        }
    }

    @Test
    void rejectsInvalidReservedHistoricalAndStaleEditsWithoutWrites() throws Exception {
        try (Database database = bootstrap(config(), "classpath:db/migration").initialize();
             var repository = ready(database, "alpha"); Connection admin = database.openConnection()) {
            SessionStart first = player("First");
            SessionStart second = player("Second");
            repository.join(first);
            repository.join(second);
            assertEquals(CHANGED, set(repository.profiles(), second.playerUuid(), "Aeron").status());
            repository.endSession(first.sessionId(), JOINED.plusSeconds(1), SessionEnd.QUIT);
            repository.join(new SessionStart(UUID.randomUUID(), first.playerUuid(), "Renamed", JOINED.plusSeconds(2)));

            for (String name : new String[]{"ab", "Has space", "Éron", "name\n", "12345678901234567"}) {
                assertEquals(INVALID, set(repository.profiles(), second.playerUuid(), name).status());
            }
            for (String name : new String[]{"FIRST", "Renamed", "CLEAR", "Moderator", "Admin", "OWNER"}) {
                assertEquals(RESERVED, set(repository.profiles(), second.playerUuid(), name).status());
            }
            assertEquals(STALE, repository.profiles().changeNickname(new NicknameChange(second.playerUuid(), 0, "Fresh")).status());
            assertEquals(NOT_FOUND, repository.profiles().changeNickname(new NicknameChange(UUID.randomUUID(), 0, "Fresh")).status());
            assertEquals(new PlayerNickname(second.playerUuid(), "Aeron", 1),
                    repository.profiles().loadNickname(second.playerUuid()).orElseThrow());
            assertEquals(1, count(admin, "SELECT count(*) FROM player_nickname_audit"));
        }
    }

    @Test
    void accountPriorityClearsOfflineNicknameAndPersistsAnOwnerScopedNotice() throws Exception {
        SessionStart owner = player("NicknameOwner");
        SessionStart claimant = player(".Aeron");
        DatabaseConfig config = config();
        UUID noticeId;
        try (Database database = bootstrap(config, "classpath:db/migration").initialize()) {
            try (var repository = ready(database, "alpha"); Connection admin = database.openConnection()) {
                repository.join(owner);
                set(repository.profiles(), owner.playerUuid(), "AeRoN");
                repository.endSession(owner.sessionId(), JOINED.plusSeconds(1), SessionEnd.QUIT);
                repository.join(claimant);
                assertEquals(new PlayerNickname(owner.playerUuid(), null, 2),
                        repository.profiles().loadNickname(owner.playerUuid()).orElseThrow());
                List<NicknameNotice> notices = repository.profiles().pendingNicknameNotices(owner.playerUuid());
                assertEquals(1, notices.size());
                assertEquals("AeRoN", notices.getFirst().removedNickname());
                noticeId = notices.getFirst().id();
                assertEquals(notices, repository.profiles().pendingNicknameNotices(owner.playerUuid()));
                assertFalse(repository.profiles().acknowledgeNicknameNotice(claimant.playerUuid(), noticeId));
                assertEquals(STALE, repository.profiles().changeNickname(new NicknameChange(owner.playerUuid(), 1, "AeRoN")).status());
                assertEquals(RESERVED, set(repository.profiles(), owner.playerUuid(), "aeron").status());
                assertEquals(1, count(admin, "SELECT count(*) FROM player_nickname_audit WHERE cause = 'ACCOUNT_NAME_CONFLICT'"));
                try (PreparedStatement query = admin.prepareStatement(
                        "SELECT actor_uuid, revision, server_id FROM player_nickname_audit WHERE id = ?")) {
                    query.setObject(1, noticeId);
                    try (ResultSet row = query.executeQuery()) {
                        assertTrue(row.next());
                        assertEquals(claimant.playerUuid(), row.getObject(1, UUID.class));
                        assertEquals(2, row.getLong(2));
                        assertEquals("alpha", row.getString(3));
                    }
                }
            }
            try (var restarted = ready(database, "alpha")) {
                assertEquals(noticeId, restarted.profiles().pendingNicknameNotices(owner.playerUuid()).getFirst().id());
                assertTrue(restarted.profiles().acknowledgeNicknameNotice(owner.playerUuid(), noticeId));
                assertFalse(restarted.profiles().acknowledgeNicknameNotice(owner.playerUuid(), noticeId));
                assertTrue(restarted.profiles().pendingNicknameNotices(owner.playerUuid()).isEmpty());
            }
        }
    }

    @Test
    void competingServerClaimsHaveExactlyOneWinner() throws Exception {
        try (Database database = bootstrap(config(), "classpath:db/migration").initialize()) {
            SessionStart first = player("First");
            SessionStart second = player("Second");
            try (var seed = ready(database, "seed")) {
                seed.join(first);
                seed.join(second);
            }
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
                Future<NicknameChangeResult> a = workers.submit(() -> competingClaim(database, "alpha", first.playerUuid(), "Aeron", ready, release));
                Future<NicknameChangeResult> b = workers.submit(() -> competingClaim(database, "beta", second.playerUuid(), "aERON", ready, release));
                try {
                    assertTrue(ready.await(5, TimeUnit.SECONDS));
                } finally {
                    release.countDown();
                }
                List<NicknameChangeResult.Status> statuses = List.of(a.get(10, TimeUnit.SECONDS).status(), b.get(10, TimeUnit.SECONDS).status());
                assertEquals(1, statuses.stream().filter(status -> status == CHANGED).count());
                assertEquals(1, statuses.stream().filter(status -> status == TAKEN).count());
            }
            try (Connection admin = database.openConnection()) {
                assertEquals(1, count(admin, "SELECT count(*) FROM player_nicknames WHERE nickname IS NOT NULL"));
                assertEquals(1, count(admin, "SELECT count(*) FROM player_nickname_audit"));
            }
        }
    }

    @Test
    void databaseConstraintsPreventCaseDuplicateInvalidNicknameAndNegativeRevision() throws Exception {
        try (Database database = bootstrap(config(), "classpath:db/migration").initialize();
             var repository = ready(database, "alpha"); Connection admin = database.openConnection()) {
            SessionStart first = player("First");
            SessionStart second = player("Second");
            repository.join(first);
            repository.join(second);
            set(repository.profiles(), first.playerUuid(), "Aeron");
            try (PreparedStatement statement = admin.prepareStatement("UPDATE player_nicknames SET nickname = ? WHERE player_uuid = ?")) {
                statement.setObject(2, second.playerUuid());
                for (String invalid : new String[]{"aERON", "ab", "<red>Aeron", "Moderator"}) {
                    statement.setString(1, invalid);
                    assertThrows(SQLException.class, statement::executeUpdate);
                }
            }
            try (Statement statement = admin.createStatement()) {
                assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE player_nicknames SET revision = -1"));
            }
        }
    }

    @Test
    void failedJoinRollsBackConflictRemovalReservationAndNoticeTogether() throws Exception {
        SessionStart owner = player("OwnerPlayer");
        SessionStart joining = player(".Aeron");
        try (Database database = bootstrap(config(), "classpath:db/migration").initialize();
             var repository = ready(database, "alpha"); Connection admin = database.openConnection()) {
            repository.join(owner);
            set(repository.profiles(), owner.playerUuid(), "Aeron");
            try (Statement statement = admin.createStatement()) {
                statement.execute("ALTER TABLE player_nickname_audit ADD CONSTRAINT fixture_reject_conflict CHECK (cause <> 'ACCOUNT_NAME_CONFLICT')");
            }
            PersistenceException failure = assertThrows(PersistenceException.class, () -> repository.join(joining));
            assertNull(failure.getCause());
            try (PreparedStatement query = admin.prepareStatement("SELECT nickname, revision FROM player_nicknames WHERE player_uuid = ?")) {
                query.setObject(1, owner.playerUuid());
                try (ResultSet row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals("Aeron", row.getString(1));
                    assertEquals(1, row.getLong(2));
                }
            }
            assertEquals(1, count(admin, "SELECT count(*) FROM players"));
            assertEquals(1, count(admin, "SELECT count(*) FROM player_sessions"));
            assertEquals(1, count(admin, "SELECT count(*) FROM player_nickname_audit"));
            assertEquals(0, count(admin, "SELECT count(*) FROM player_account_names WHERE name_key IN ('.aeron', 'aeron')"));
        }
    }

    @Test
    void failedNicknameAuditRollsBackReplacementAndInterruptionPreservesState() throws Exception {
        SessionStart owner = player("OwnerPlayer");
        try (Database database = bootstrap(config(), "classpath:db/migration").initialize();
             var repository = ready(database, "alpha"); Connection admin = database.openConnection()) {
            repository.join(owner);
            set(repository.profiles(), owner.playerUuid(), "Aeron");
            Thread.currentThread().interrupt();
            try {
                assertEquals(PersistenceException.Kind.CANCELLED, assertThrows(PersistenceException.class,
                        () -> set(repository.profiles(), owner.playerUuid(), "Comet")).kind());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            assertEquals("Aeron", repository.profiles().loadNickname(owner.playerUuid()).orElseThrow().nickname());
            try (Statement statement = admin.createStatement()) {
                statement.execute("ALTER TABLE player_nickname_audit ADD CONSTRAINT fixture_reject_name CHECK (new_nickname <> 'Comet')");
            }
            PersistenceException failure = assertThrows(PersistenceException.class,
                    () -> set(repository.profiles(), owner.playerUuid(), "Comet"));
            assertNull(failure.getCause());
            assertEquals(1, count(admin, "SELECT count(*) FROM player_nicknames WHERE nickname = 'Aeron' AND revision = 1"));
            assertEquals(1, count(admin, "SELECT count(*) FROM player_nickname_audit"));
        }
    }

    @Test
    void accountJoinWinsAConcurrentClaimFromAnotherServer() throws Exception {
        try (Database database = bootstrap(config(), "classpath:db/migration").initialize()) {
            SessionStart owner = player("OwnerPlayer");
            SessionStart joining = player("Aeron");
            try (var seed = ready(database, "seed")) {
                seed.join(owner);
            }
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
                Future<NicknameChangeResult> claim = workers.submit(() ->
                        competingClaim(database, "alpha", owner.playerUuid(), "Aeron", ready, release));
                Future<?> join = workers.submit(() -> {
                    try (var repository = ready(database, "beta")) {
                        ready.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        repository.join(joining);
                    }
                    return null;
                });
                try {
                    assertTrue(ready.await(5, TimeUnit.SECONDS));
                } finally {
                    release.countDown();
                }
                assertTrue(List.of(CHANGED, RESERVED).contains(claim.get(10, TimeUnit.SECONDS).status()));
                join.get(10, TimeUnit.SECONDS);
            }
            try (var inspect = ready(database, "inspect")) {
                assertNull(inspect.profiles().loadNickname(owner.playerUuid()).orElseThrow().nickname());
                assertEquals("Aeron", inspect.findPlayer(joining.playerUuid()).orElseThrow().username());
                assertEquals(RESERVED, set(inspect.profiles(), owner.playerUuid(), "Aeron").status());
            }
        }
    }

    @Test
    void noticesAreBoundedAndReadsNeverAcknowledgeThem() throws Exception {
        try (Database database = bootstrap(config(), "classpath:db/migration").initialize();
             var repository = ready(database, "alpha"); Connection admin = database.openConnection()) {
            SessionStart owner = player("OwnerPlayer");
            SessionStart actor = player("OtherPlayer");
            repository.join(owner);
            repository.join(actor);
            try (PreparedStatement fixture = admin.prepareStatement("""
                    INSERT INTO player_nickname_audit
                        (id, player_uuid, server_id, actor_uuid, cause, previous_nickname, revision)
                    SELECT gen_random_uuid(), ?, 'alpha', ?, 'ACCOUNT_NAME_CONFLICT', 'Aeron', sequence
                    FROM generate_series(1, 65) AS sequence
                    """)) {
                fixture.setObject(1, owner.playerUuid());
                fixture.setObject(2, actor.playerUuid());
                fixture.executeUpdate();
            }
            List<NicknameNotice> first = repository.profiles().pendingNicknameNotices(owner.playerUuid());
            assertEquals(64, first.size());
            assertEquals(first, repository.profiles().pendingNicknameNotices(owner.playerUuid()));
            assertTrue(repository.profiles().acknowledgeNicknameNotice(owner.playerUuid(), first.getFirst().id()));
            List<NicknameNotice> after = repository.profiles().pendingNicknameNotices(owner.playerUuid());
            assertEquals(64, after.size());
            assertFalse(after.contains(first.getFirst()));
        }
    }

    @Test
    void profileViewCannotOutliveOwnershipOrSilentlyDefaultAMissingRow() throws Exception {
        try (Database database = bootstrap(config(), "classpath:db/migration").initialize()) {
            SessionStart owner = player("OwnerPlayer");
            PlayerProfileRepository retained;
            try (var repository = ready(database, "alpha")) {
                repository.join(owner);
                retained = repository.profiles();
            }
            assertEquals(PersistenceException.Kind.OWNERSHIP_LOST, assertThrows(PersistenceException.class,
                    () -> retained.loadNickname(owner.playerUuid())).kind());
            try (var repository = ready(database, "beta"); Connection admin = database.openConnection();
                 PreparedStatement delete = admin.prepareStatement("DELETE FROM player_nicknames WHERE player_uuid = ?")) {
                delete.setObject(1, owner.playerUuid());
                delete.executeUpdate();
                PersistenceException failure = assertThrows(PersistenceException.class,
                        () -> repository.profiles().loadNickname(owner.playerUuid()));
                assertEquals(PersistenceException.Kind.DATABASE_FAILURE, failure.kind());
                assertNull(failure.getCause());
                assertFalse(failure.getMessage().contains(owner.playerUuid().toString()));
            }
        }
    }

    @Test
    void interruptionAfterTheWriteRollsBackNicknameAndAuditBeforeCommit() throws Exception {
        try (Database database = bootstrap(config(), "classpath:db/migration").initialize();
             Connection fixture = database.openConnection()) {
            SessionStart owner = player("OwnerPlayer");
            try (var seed = ready(database, "seed")) {
                seed.join(owner);
            }
            try (Statement statement = fixture.createStatement()) {
                statement.execute("""
                        CREATE FUNCTION fixture_hold_nickname_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN
                            PERFORM pg_advisory_xact_lock(199901, 0);
                            RETURN NEW;
                        END $$
                        """);
                statement.execute("CREATE TRIGGER fixture_hold BEFORE INSERT ON player_nickname_audit FOR EACH ROW EXECUTE FUNCTION fixture_hold_nickname_audit()");
                statement.execute("SELECT pg_advisory_lock(199901, 0)");
            }
            AtomicReference<Thread> writerThread = new AtomicReference<>();
            try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
                Future<Boolean> writer = worker.submit(() -> {
                    try (var repository = ready(database, "writer")) {
                        writerThread.set(Thread.currentThread());
                        PersistenceException failure = assertThrows(PersistenceException.class,
                                () -> repository.profiles().changeNickname(new NicknameChange(owner.playerUuid(), 0, "Aeron")));
                        assertEquals(PersistenceException.Kind.CANCELLED, failure.kind());
                        return Thread.currentThread().isInterrupted();
                    }
                });
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    boolean waiting = false;
                    while (System.nanoTime() < deadline) {
                        waiting = count(fixture, "SELECT count(*) FROM pg_stat_activity WHERE wait_event = 'advisory' AND query LIKE 'INSERT INTO player_nickname_audit%' ") > 0;
                        if (waiting) {
                            break;
                        }
                        Thread.sleep(5);
                    }
                    assertTrue(waiting);
                    writerThread.get().interrupt();
                } finally {
                    try (Statement statement = fixture.createStatement()) {
                        statement.execute("SELECT pg_advisory_unlock(199901, 0)");
                    }
                }
                assertTrue(writer.get(5, TimeUnit.SECONDS));
            }
            assertEquals(0, count(fixture, "SELECT count(*) FROM player_nickname_audit"));
            assertEquals(1, count(fixture, "SELECT count(*) FROM player_nicknames WHERE nickname IS NULL AND revision = 0"));
        }
    }
}
