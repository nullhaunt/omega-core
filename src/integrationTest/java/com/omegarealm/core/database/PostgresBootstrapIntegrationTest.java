package com.omegarealm.core.database;

import com.omegarealm.core.config.DatabaseConfig;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class PostgresBootstrapIntegrationTest {

    @TempDir
    static Path directory;

    private static EmbeddedPostgres postgres;

    private final List<String> messages = new CopyOnWriteArrayList<>();

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
        String name = "omega_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = postgres.getPostgresDatabase().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }

        return new DatabaseConfig("127.0.0.1", postgres.getPort(), name, "postgres", "TEST_PASSWORD", 2);
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static void assertPoolThreadsTerminated() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive()
                    && thread.getName().startsWith("omega-database"))) {
                return;
            }

            Thread.sleep(10);
        }

        fail("Pool threads did not terminate after resource cleanup");
    }

    @Test
    void createsSchemaTracksMigrationsAndPreservesDataOnRestart() throws Exception {
        DatabaseConfig config = isolatedConfig();
        PostgresBootstrap bootstrap = bootstrap(config, "classpath:db/migration");

        try (Database database = bootstrap.initialize(); Connection connection = database.openConnection()) {
            assertEquals(6, scalar(connection, "SELECT count(*) FROM flyway_schema_history WHERE success"));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('players','player_sessions')"));
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO players(uuid,username,first_seen_at,last_seen_at) VALUES ('00000000-0000-0000-0000-000000000001','TestPlayer',now(),now())");
            }
        }

        try (Database database = bootstrap.initialize(); Connection connection = database.openConnection()) {
            assertEquals(6, scalar(connection, "SELECT count(*) FROM flyway_schema_history WHERE success"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM players"));
            assertTrue(messages.stream().anyMatch(message -> message.contains("applied 0 migration(s)")));
        }
    }

    @Test
    void enforcesSchemaRelationshipsAndSessionTimeOrder() throws Exception {
        try (Database database = bootstrap(isolatedConfig(), "classpath:db/migration").initialize();
             Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO player_sessions(id,player_uuid,server_id,joined_at) VALUES ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','test',now())"));
            statement.executeUpdate("INSERT INTO players(uuid,username,first_seen_at,last_seen_at) VALUES ('00000000-0000-0000-0000-000000000002','TestPlayer',now(),now())");
            assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO player_sessions(id,player_uuid,server_id,joined_at,left_at) VALUES ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','test',now(),now()-interval '1 hour')"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedMigrationRollsBackAndDoesNotExposeSqlText(boolean debug) throws Exception {
        DatabaseConfig config = isolatedConfig();
        DatabaseException failure = assertThrows(DatabaseException.class,
                () -> bootstrap(config, "classpath:db/broken", debug).initialize());

        assertTrue(failure.getMessage().contains("migration failed"));
        assertTrue(failure.getMessage().contains("SQLSTATE 42703"), failure::getMessage);
        assertTrue(failure.getMessage().contains("Migration version=001"), failure::getMessage);
        assertFalse(failure.getMessage().contains("DO_NOT_LOG_THIS"));
        assertNull(failure.getCause());
        assertFalse(messages.stream().anyMatch(message -> message.contains("DO_NOT_LOG_THIS")));

        try (Connection connection = postgres.getDatabase("postgres", config.database()).getConnection()) {
            assertEquals(0, scalar(connection, "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='rolled_back_probe'"));
        }

        assertPoolThreadsTerminated();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void checksumMismatchPreventsStartup(boolean debug) throws Exception {
        DatabaseConfig config = isolatedConfig();
        Path migrations = Files.createDirectory(directory.resolve("checksum-" + UUID.randomUUID()));
        Path script = migrations.resolve("V001__create_players.sql");
        try (InputStream source = getClass().getResourceAsStream("/db/migration/V001__create_players.sql")) {
            assertNotNull(source);
            Files.writeString(script, new String(source.readAllBytes(), StandardCharsets.UTF_8));
        }
        PostgresBootstrap bootstrap = bootstrap(config, "filesystem:" + migrations, debug);
        try (Database database = bootstrap.initialize()) {
            assertNotNull(database);
        }

        Files.writeString(script, Files.readString(script).replace("VARCHAR(16)", "VARCHAR(32)"));
        DatabaseException failure = assertThrows(DatabaseException.class, bootstrap::initialize);

        assertTrue(failure.getMessage().contains("migration failed"));
        assertTrue(failure.getMessage().contains("CHECKSUM_MISMATCH, version=001"));
        assertTrue(messages.stream().anyMatch(message -> message.contains("CHECKSUM_MISMATCH, version=001")));
        assertEquals(debug, messages.stream().anyMatch(message -> message.contains("bootstrap debug:")));
        assertPoolThreadsTerminated();
    }

    @Test
    void refusesToBaselineAnUnmanagedNonemptySchema() throws Exception {
        DatabaseConfig config = isolatedConfig();
        try (Connection connection = postgres.getDatabase("postgres", config.database()).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE unmanaged_data (id INTEGER)");
        }

        DatabaseException failure = assertThrows(DatabaseException.class,
                () -> bootstrap(config, "classpath:db/migration").initialize());
        assertTrue(failure.getMessage().contains("NON_EMPTY_SCHEMA_WITHOUT_SCHEMA_HISTORY_TABLE"));
        assertTrue(failure.getMessage().contains("cannot be adopted automatically"));
        assertPoolThreadsTerminated();
    }

    @Test
    void poolIsBoundedAndCannotBeUsedAfterClose() throws Exception {
        DatabaseConfig config = isolatedConfig();
        Database database = bootstrap(config, "classpath:db/migration").initialize();
        try {
            try (Connection first = database.openConnection(); Connection second = database.openConnection()) {
                assertEquals(2, scalar(first, "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND application_name='OmegaCore'"));
                assertNotSame(first, second);
            }
        } finally {
            database.close();
        }

        assertThrows(SQLException.class, database::openConnection);
        assertPoolThreadsTerminated();
    }

    @Test
    void refusedConnectionFailsWithinTheBootstrapConnectionBound() throws Exception {
        int closedPort;
        try (ServerSocket reserved = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = reserved.getLocalPort();
        }
        DatabaseConfig config = new DatabaseConfig("127.0.0.1", closedPort, "unreachable", "postgres", "TEST_PASSWORD", 2);
        long started = System.nanoTime();

        DatabaseException failure = assertThrows(DatabaseException.class,
                () -> bootstrap(config, "classpath:db/migration").initialize());

        assertTrue(failure.getMessage().contains("connection failed"));
        assertTrue(System.nanoTime() - started < 20_000_000_000L);
        assertPoolThreadsTerminated();
    }

    @Test
    void interruptedMigrationStillClosesThePoolAndItsThreads() throws Exception {
        DatabaseConfig config = isolatedConfig();
        ExecutorService initializer = Executors.newSingleThreadExecutor();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean transferred = new AtomicBoolean();
        try {
            Future<?> startup = initializer.submit(() -> {
                try (Database database = bootstrap(config, "classpath:db/cancel").initialize()) {
                    transferred.set(true);
                } catch (DatabaseException cancelled) {
                    failure.set(cancelled);
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean executingMigration = false;
            try (Connection connection = postgres.getDatabase("postgres", config.database()).getConnection()) {
                while (System.nanoTime() < deadline) {
                    if (scalar(connection, "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND application_name='OmegaCore' AND state='active' AND query LIKE '%pg_sleep(2)%'") > 0) {
                        executingMigration = true;
                        break;
                    }

                    Thread.sleep(10);
                }
            }
            assertTrue(executingMigration, "Cancellation must occur while the real migration is running");
            assertTrue(startup.cancel(true));
            initializer.shutdown();
            assertTrue(initializer.awaitTermination(10, TimeUnit.SECONDS));
            assertInstanceOf(DatabaseException.class, failure.get());
            assertFalse(transferred.get(), "Interrupted startup must not transfer a ready database");
            assertPoolThreadsTerminated();
        } finally {
            initializer.shutdownNow();
            assertTrue(initializer.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private PostgresBootstrap bootstrap(DatabaseConfig config, String location) {
        return bootstrap(config, location, false);
    }

    private PostgresBootstrap bootstrap(DatabaseConfig config, String location, boolean debug) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return new PostgresBootstrap(config, ignored -> "test-only-password", logger,
                getClass().getClassLoader(), location, debug);
    }
}
