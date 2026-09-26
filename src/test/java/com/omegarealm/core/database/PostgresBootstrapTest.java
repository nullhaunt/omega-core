package com.omegarealm.core.database;

import com.omegarealm.core.config.DatabaseConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class PostgresBootstrapTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingCredentialsBeforeOpeningAPool(String value) {
        AtomicReference<String> requested = new AtomicReference<>();
        DatabaseConfig config = new DatabaseConfig("localhost", 5432, "omega", "omega", "TEST_PASSWORD", 5);
        PostgresBootstrap bootstrap = new PostgresBootstrap(config, name -> {
            requested.set(name);
            return value;
        }, Logger.getAnonymousLogger(), getClass().getClassLoader());

        DatabaseException failure = assertThrows(DatabaseException.class, bootstrap::initialize);

        assertEquals("TEST_PASSWORD", requested.get());
        assertTrue(failure.getMessage().contains("database.password-env"));
        assertNull(failure.getCause());
    }

    @Test
    void summarizesSqlFailureWithoutItsMessageOrCause() {
        SQLException driver = new SQLException("DO_NOT_LOG_THIS", "28P01");
        DatabaseException failure = DatabaseException.failure("connection", new IllegalStateException(driver));

        assertEquals("Database connection failed (SQLSTATE 28P01).", failure.getMessage());
        assertNull(failure.getCause());
    }
}
