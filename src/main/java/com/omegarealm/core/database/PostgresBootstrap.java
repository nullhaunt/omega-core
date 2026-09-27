package com.omegarealm.core.database;

import com.omegarealm.core.config.DatabaseConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Logger;

public final class PostgresBootstrap implements DatabaseBootstrap {

    private final DatabaseConfig config;
    private final Function<String, String> environment;
    private final Logger logger;
    private final ClassLoader classLoader;
    private final String migrationLocation;
    private final boolean debug;

    public PostgresBootstrap(DatabaseConfig config, Function<String, String> environment,
                             Logger logger, ClassLoader classLoader) {
        this(config, environment, logger, classLoader, "classpath:db/migration", false);
    }

    public PostgresBootstrap(DatabaseConfig config, Function<String, String> environment,
                             Logger logger, ClassLoader classLoader, boolean debug) {
        this(config, environment, logger, classLoader, "classpath:db/migration", debug);
    }

    PostgresBootstrap(DatabaseConfig config, Function<String, String> environment,
                      Logger logger, ClassLoader classLoader, String migrationLocation) {
        this(config, environment, logger, classLoader, migrationLocation, false);
    }

    PostgresBootstrap(DatabaseConfig config, Function<String, String> environment,
                      Logger logger, ClassLoader classLoader, String migrationLocation, boolean debug) {
        this.config = Objects.requireNonNull(config, "config");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.migrationLocation = Objects.requireNonNull(migrationLocation, "migrationLocation");
        this.debug = debug;
    }

    private static void checkConnection(HikariDataSource pool) throws SQLException {
        try (Connection connection = pool.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(15);

            try (ResultSet result = statement.executeQuery("SELECT 1")) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new SQLException("Unexpected database health result", "08006");
                }
            }
        }
    }

    private static void checkInterrupted() throws DatabaseException {
        if (Thread.currentThread().isInterrupted()) {
            throw new DatabaseException("Database startup cancelled.");
        }
    }

    @Override
    public Database initialize() throws DatabaseException {
        long started = System.nanoTime();
        debugStage("credentials.check", started);
        String password = environment.apply(config.passwordEnvironment());
        if (password == null || password.isBlank()) {
            throw new DatabaseException("Database credentials unavailable: the environment variable named by database.password-env must be nonblank.");
        }

        HikariDataSource pool = null;
        boolean transferred = false;
        String phase = "connection";
        MigrationDiagnostics diagnostics = new MigrationDiagnostics(logger, debug);

        try {
            checkInterrupted();
            debugStage("connection.begin", started);
            pool = createPool(password);
            checkConnection(pool);
            debugStage("connection.complete", started);
            checkInterrupted();

            phase = "migration";
            debugStage("migration.discovery", started);
            Flyway flyway = Flyway.configure(classLoader)
                    .dataSource(pool)
                    .locations(migrationLocation)
                    .failOnMissingLocations(true)
                    .validateMigrationNaming(true)
                    .validateOnMigrate(true)
                    // Match migrate's normal allowance for pending scripts and future history.
                    .ignoreMigrationPatterns("*:future", "*:pending")
                    .baselineOnMigrate(false)
                    .cleanDisabled(true)
                    .connectRetries(0)
                    .lockRetryCount(5)
                    .loggers(MigrationLogCreator.class.getName())
                    .callbacks(diagnostics)
                    .load();
            diagnostics.validation(flyway.validateWithResult());
            checkInterrupted();
            int applied = flyway.migrate().migrationsExecuted;

            checkInterrupted();
            phase = "health check";
            debugStage("health-check.begin", started);
            checkConnection(pool);
            debugStage("health-check.complete", started);
            logger.info("Database migrations validated; applied " + applied + " migration(s).");

            transferred = true;
            return new PostgresDatabase(pool);
        } catch (SQLException | RuntimeException failure) {
            DatabaseException summary = DatabaseException.failure(phase, failure);
            String version = diagnostics.activeVersion();
            throw version == null ? summary
                    : new DatabaseException(summary.getMessage() + " Migration version=" + version + ".");
        } finally {
            if (!transferred && pool != null) {
                PostgresDatabase.closePool(pool);
                debugStage("cleanup.complete", started);
            }
        }
    }

    private void debugStage(String stage, long started) {
        if (debug) {
            long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            logger.info("Database bootstrap debug: stage=" + stage + ", elapsed-ms=" + elapsed + ".");
        }
    }

    private HikariDataSource createPool(String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setServerNames(new String[]{config.host()});
        source.setPortNumbers(new int[]{config.port()});
        source.setDatabaseName(config.database());
        source.setUser(config.username());
        source.setPassword(password);
        source.setConnectTimeout(5);
        source.setSocketTimeout(15);
        source.setTcpKeepAlive(true);
        source.setApplicationName("OmegaCore");
        source.setOptions("-c statement_timeout=15000 -c lock_timeout=10000");

        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setExceptionOverride(new ControlledSqlExceptions());
        poolConfig.setDataSource(source);
        poolConfig.setPoolName("omega-database");
        poolConfig.setMaximumPoolSize(config.poolSize());
        poolConfig.setMinimumIdle(0);
        poolConfig.setConnectionTimeout(10000);
        poolConfig.setValidationTimeout(5000);
        poolConfig.setInitializationFailTimeout(-1);

        return new HikariDataSource(poolConfig);
    }
}
