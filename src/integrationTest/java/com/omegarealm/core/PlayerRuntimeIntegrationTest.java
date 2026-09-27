package com.omegarealm.core;

import com.omegarealm.core.config.DatabaseConfig;
import com.omegarealm.core.config.OmegaConfig;
import com.omegarealm.core.config.ServerConfig;
import com.omegarealm.core.database.Database;
import com.omegarealm.core.database.PostgresBootstrap;
import com.omegarealm.core.player.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(40)
class PlayerRuntimeIntegrationTest {

    @TempDir
    static Path directory;
    private static EmbeddedPostgres postgres;
    private final List<OmegaRuntime> runtimes = new ArrayList<>();
    private final AtomicInteger shutdowns = new AtomicInteger();

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

    private static DatabaseConfig isolatedConfig() throws Exception {
        String name = "omega_runtime_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = postgres.getPostgresDatabase().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return new DatabaseConfig("127.0.0.1", postgres.getPort(), name, "postgres", "TEST_PASSWORD", 3);
    }

    private static PostgresBootstrap bootstrap(DatabaseConfig database) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        return new PostgresBootstrap(database, ignored -> "test-only-password", logger,
                PlayerRuntimeIntegrationTest.class.getClassLoader());
    }

    private static Connection admin(DatabaseConfig database) throws SQLException {
        return postgres.getDatabase("postgres", database.database()).getConnection();
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static PlayerAttempt attempt() {
        return new PlayerAttempt(UUID.randomUUID(), new PlayerIdentity(UUID.randomUUID(), "Player"));
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static void ready(OmegaRuntime runtime) throws Exception {
        runtime.readiness().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(RuntimeState.READY, runtime.state());
    }

    private static void assertUnavailable(CompletionStage<?> stage) {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> stage.toCompletableFuture().get(2, TimeUnit.SECONDS));
        assertEquals(PlayerOperationException.Kind.UNAVAILABLE,
                assertInstanceOf(PlayerOperationException.class, failure.getCause()).kind());
        assertNull(failure.getCause().getCause());
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }

    private static void waitForQuery(DatabaseConfig database, String condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean executing = false;
        try (Connection admin = admin(database)) {
            while (System.nanoTime() < deadline) {
                if (scalar(admin, "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND application_name = 'OmegaCore' AND " + condition) > 0) {
                    executing = true;
                    break;
                }
                Thread.sleep(10);
            }
        }
        assertTrue(executing, "Expected real PostgreSQL operation did not start");
    }

    @AfterEach
    void stopRuntimes() throws Exception {
        for (OmegaRuntime runtime : runtimes) {
            runtime.stop();
        }
        for (OmegaRuntime runtime : runtimes) {
            assertTrue(runtime.awaitStopped(Duration.ofSeconds(20)));
        }
    }

    @Test
    void startupRecoversOnlyOwnedServerBeforeReadiness() throws Exception {
        DatabaseConfig config = isolatedConfig();
        Instant beforeCrash = Instant.now().minusSeconds(3600);
        UUID alphaPlayer = UUID.randomUUID();
        try (Database database = bootstrap(config).initialize();
             var alpha = PostgresPlayerSessionRepository.acquire(database, "alpha");
             var beta = PostgresPlayerSessionRepository.acquire(database, "beta")) {
            alpha.recoverInterrupted(beforeCrash);
            beta.recoverInterrupted(beforeCrash);
            alpha.join(new SessionStart(UUID.randomUUID(), alphaPlayer, "Player", beforeCrash));
            beta.join(new SessionStart(UUID.randomUUID(), UUID.randomUUID(), "Other", beforeCrash));
        }

        OmegaRuntime runtime = runtime(config, "alpha");
        ready(runtime);
        assertTrue(runtime.players().isRunning());
        try (Connection admin = admin(config)) {
            assertEquals(1, scalar(admin, "SELECT count(*) FROM player_sessions WHERE server_id = 'alpha' AND end_reason = 'INTERRUPTED' AND left_at IS NULL AND recovered_at IS NOT NULL"));
            assertEquals(1, scalar(admin, "SELECT count(*) FROM player_sessions WHERE server_id = 'beta' AND end_reason IS NULL"));
        }
        PlayerAttempt reconnect = new PlayerAttempt(UUID.randomUUID(), new PlayerIdentity(alphaPlayer, "Player"));
        PlayerProfile stored = await(runtime.players().prepare(reconnect)).storedProfile().orElseThrow();
        assertEquals(beforeCrash.truncatedTo(java.time.temporal.ChronoUnit.MICROS), stored.lastSeenAt());
        assertEquals(0, shutdowns.get());
    }

    @Test
    void serviceRecordsJoinQuitReconnectAndShutdownInRealDatabase() throws Exception {
        DatabaseConfig config = isolatedConfig();
        OmegaRuntime runtime = runtime(config, "alpha");
        ready(runtime);
        PlayerService service = runtime.players();
        PlayerAttempt first = attempt();
        assertTrue(await(service.prepare(first)).storedProfile().isEmpty());
        try (Connection admin = admin(config)) {
            assertEquals(0, scalar(admin, "SELECT count(*) FROM players"));
            assertEquals(0, scalar(admin, "SELECT count(*) FROM player_sessions"));
        }
        Instant joined = Instant.now().minusSeconds(10);
        PlayerProfile original = await(service.activate(first.token(), joined));
        await(service.disconnect(first.token(), joined.plusSeconds(1)));

        PlayerAttempt reconnect = new PlayerAttempt(UUID.randomUUID(),
                new PlayerIdentity(first.identity().uuid(), "Renamed"));
        assertEquals(original.firstSeenAt(), await(service.prepare(reconnect)).storedProfile().orElseThrow().firstSeenAt());
        PlayerProfile renamed = await(service.activate(reconnect.token(), joined.plusSeconds(2)));
        assertEquals("Renamed", renamed.username());
        assertEquals(original.firstSeenAt(), renamed.firstSeenAt());
        await(service.disconnect(first.token(), Instant.now()));
        runtime.stop();
        assertTrue(runtime.awaitStopped(Duration.ofSeconds(5)));

        try (Connection admin = admin(config)) {
            assertEquals(1, scalar(admin, "SELECT count(*) FROM players"));
            assertEquals(2, scalar(admin, "SELECT count(*) FROM player_sessions"));
            assertEquals(1, scalar(admin, "SELECT count(*) FROM player_sessions WHERE end_reason = 'QUIT'"));
            assertEquals(1, scalar(admin, "SELECT count(*) FROM player_sessions WHERE end_reason = 'SHUTDOWN' AND left_at >= joined_at"));
            assertEquals(0, scalar(admin, "SELECT count(*) FROM player_sessions WHERE end_reason IS NULL"));
        }
        OmegaRuntime restarted = runtime(config, "alpha");
        ready(restarted);
        assertEquals(0, shutdowns.get());
    }

    @Test
    void duplicateRuntimeFailsClosedWithoutDisplacingOwner() throws Exception {
        DatabaseConfig config = isolatedConfig();
        OmegaRuntime alpha = runtime(config, "alpha");
        ready(alpha);
        OmegaRuntime duplicate = runtime(config, "alpha");
        ExecutionException rejection = assertThrows(ExecutionException.class,
                () -> duplicate.readiness().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertTrue(rejection.getCause().getMessage().contains("already owned"));
        assertNull(rejection.getCause().getCause());
        assertEquals(RuntimeState.FAILED, duplicate.state());
        waitUntil(() -> shutdowns.get() == 1);
        duplicate.stop();
        assertTrue(duplicate.awaitStopped(Duration.ofSeconds(5)));

        assertEquals(RuntimeState.READY, alpha.state());
        await(alpha.players().prepare(attempt()));
        OmegaRuntime beta = runtime(config, "beta");
        ready(beta);
        assertEquals(RuntimeState.READY, beta.state());
        assertEquals(1, shutdowns.get());
    }

    @Test
    void idleBackendLossClosesAdmissionAndReplacementCanRecover() throws Exception {
        DatabaseConfig config = isolatedConfig();
        OmegaRuntime runtime = runtime(config, "alpha");
        ready(runtime);
        PlayerService obsolete = runtime.players();
        PlayerAttempt player = attempt();
        await(obsolete.prepare(player));
        await(obsolete.activate(player.token(), Instant.now().minusSeconds(1)));
        try (Connection admin = admin(config); Statement statement = admin.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT pg_terminate_backend(pid) FROM pg_locks
                     WHERE locktype = 'advisory' AND classid::bigint = 1330464583
                       AND objid::bigint = (SELECT id FROM omega_servers WHERE server_id = 'alpha')
                       AND database = (SELECT oid FROM pg_database WHERE datname = current_database())
                     """)) {
            assertTrue(result.next());
            assertTrue(result.getBoolean(1));
        }
        waitUntil(() -> runtime.state() == RuntimeState.FAILED);
        waitUntil(() -> shutdowns.get() == 1);
        assertThrows(IllegalStateException.class, runtime::players);
        assertUnavailable(obsolete.prepare(attempt()));
        runtime.stop();
        assertTrue(runtime.awaitStopped(Duration.ofSeconds(5)));

        OmegaRuntime replacement = runtime(config, "alpha");
        ready(replacement);
        try (Connection admin = admin(config)) {
            assertEquals(1, scalar(admin, "SELECT count(*) FROM player_sessions WHERE end_reason = 'INTERRUPTED' AND left_at IS NULL"));
        }
        assertEquals(1, shutdowns.get());
    }

    @Test
    void disconnectDuringBlockedReadCannotPublishOrWriteForOldAttempt() throws Exception {
        DatabaseConfig config = isolatedConfig();
        OmegaRuntime runtime = runtime(config, "alpha");
        ready(runtime);
        PlayerService service = runtime.players();
        PlayerAttempt first = attempt();
        CompletionStage<PreparedPlayer> reconnecting;
        PlayerAttempt reconnect = new PlayerAttempt(UUID.randomUUID(), first.identity());
        try (Connection blocker = admin(config); Statement statement = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            statement.execute("LOCK TABLE players IN ACCESS EXCLUSIVE MODE");
            CompletionStage<PreparedPlayer> blocked = service.prepare(first);
            waitForQuery(config, "wait_event_type = 'Lock' AND query LIKE '%LEFT JOIN player_authorizations%'");
            await(service.disconnect(first.token(), Instant.now()));
            ExecutionException cancelled = assertThrows(ExecutionException.class,
                    () -> blocked.toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(PlayerOperationException.Kind.CANCELLED,
                    assertInstanceOf(PlayerOperationException.class, cancelled.getCause()).kind());
            reconnecting = service.prepare(reconnect);
            blocker.rollback();
        }
        await(reconnecting);
        assertFalse(service.isPrepared(first.token()));
        assertTrue(service.isPrepared(reconnect.token()));
        try (Connection admin = admin(config)) {
            assertEquals(0, scalar(admin, "SELECT count(*) FROM players"));
            assertEquals(0, scalar(admin, "SELECT count(*) FROM player_sessions"));
        }
        await(service.activate(reconnect.token(), Instant.now().minusSeconds(1)));
        await(service.disconnect(first.token(), Instant.now()));
        try (Connection admin = admin(config)) {
            assertEquals(1, scalar(admin, "SELECT count(*) FROM player_sessions WHERE end_reason IS NULL"));
        }
        assertEquals(0, shutdowns.get());
    }

    @Test
    void shutdownDuringRealJoinClosesLateCommitBeforeReleasingOwnership() throws Exception {
        DatabaseConfig config = isolatedConfig();
        OmegaRuntime runtime = runtime(config, "alpha");
        ready(runtime);
        PlayerService service = runtime.players();
        PlayerAttempt player = attempt();
        await(service.prepare(player));
        try (Connection admin = admin(config); Statement statement = admin.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION delay_session() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN PERFORM pg_sleep(2); RETURN NEW; END $$
                    """);
            statement.execute("CREATE TRIGGER delay_session BEFORE INSERT ON player_sessions FOR EACH ROW EXECUTE FUNCTION delay_session()");
        }
        CompletionStage<PlayerProfile> joining = service.activate(player.token(), Instant.now().minusSeconds(1));
        waitForQuery(config, "wait_event = 'PgSleep'");
        runtime.stop();
        assertUnavailable(joining);
        assertTrue(runtime.awaitStopped(Duration.ofSeconds(8)));
        try (Connection admin = admin(config)) {
            assertEquals(1, scalar(admin, "SELECT count(*) FROM player_sessions WHERE end_reason = 'SHUTDOWN' AND left_at >= joined_at"));
            assertEquals(0, scalar(admin, "SELECT count(*) FROM player_sessions WHERE end_reason IS NULL"));
        }
        OmegaRuntime replacement = runtime(config, "alpha");
        ready(replacement);
        assertEquals(0, shutdowns.get());
    }

    @Test
    void stopDuringAcquisitionDiscardsPartiallyAcquiredLockAndPool() throws Exception {
        DatabaseConfig config = isolatedConfig();
        try (Database migrated = bootstrap(config).initialize()) {
            assertNotNull(migrated);
        }
        OmegaRuntime runtime;
        try (Connection blocker = admin(config); Statement statement = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            statement.execute("LOCK TABLE omega_servers IN ACCESS EXCLUSIVE MODE");
            runtime = runtime(config, "alpha");
            waitForQuery(config, "wait_event_type = 'Lock' AND query LIKE '%INSERT INTO omega_servers%'");
            runtime.stop();
            blocker.rollback();
        }
        assertTrue(runtime.awaitStopped(Duration.ofSeconds(8)));
        assertEquals(RuntimeState.STOPPED, runtime.state());
        assertTrue(runtime.readiness().toCompletableFuture().isCompletedExceptionally());
        OmegaRuntime replacement = runtime(config, "alpha");
        ready(replacement);
        assertEquals(0, shutdowns.get());
    }

    private OmegaRuntime runtime(DatabaseConfig database, String serverId) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        PlayerServiceLimits limits = new PlayerServiceLimits(64, 256, Duration.ofSeconds(15),
                Duration.ofSeconds(30), Duration.ofSeconds(15), Duration.ofMillis(100),
                Duration.ofSeconds(3), Duration.ofSeconds(20));
        OmegaRuntime runtime = new OmegaRuntime(new OmegaConfig(new ServerConfig(serverId, "development"),
                database, false), logger, bootstrap(database), Runnable::run,
                shutdowns::incrementAndGet, Duration.ofSeconds(15),
                PostgresPlayerSessionRepository::acquire, limits);
        runtimes.add(runtime);
        runtime.start();
        return runtime;
    }
}
