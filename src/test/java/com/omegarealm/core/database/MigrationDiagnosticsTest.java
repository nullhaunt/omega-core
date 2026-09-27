package com.omegarealm.core.database;

import org.flywaydb.core.api.CoreErrorCode;
import org.flywaydb.core.api.ErrorCode;
import org.flywaydb.core.api.ErrorDetails;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.ValidateOutput;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class MigrationDiagnosticsTest {

    private static final ErrorCode UNTRUSTED_CODE = new ErrorCode() {
        @Override
        public String toString() {
            throw new AssertionError("Untrusted error codes must never be formatted");
        }
    };

    private final List<String> messages = new ArrayList<>();

    private static ValidateResult result(boolean valid, List<ValidateOutput> errors) {
        return new ValidateResult("DO_NOT_LOG_VERSION", "DO_NOT_LOG_DATABASE",
                new ErrorDetails(CoreErrorCode.VALIDATE_ERROR, "DO_NOT_LOG_ERROR"),
                valid, 4, errors, List.of("DO_NOT_LOG_WARNING"));
    }

    private MigrationDiagnostics diagnostics(boolean debug) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                assertNull(record.getThrown());
                messages.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return new MigrationDiagnostics(logger, debug);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reportsActionableValidationFailureWithoutExternalText(boolean debug) {
        var invalid = new ValidateOutput("004", "DO_NOT_LOG_DESCRIPTION", "DO_NOT_LOG_PATH",
                new ErrorDetails(CoreErrorCode.CHECKSUM_MISMATCH, "DO_NOT_LOG_SQL"));

        DatabaseException failure = assertThrows(DatabaseException.class,
                () -> diagnostics(debug).validation(result(false, List.of(invalid))));

        assertTrue(failure.getMessage().contains("CHECKSUM_MISMATCH, version=004"));
        assertTrue(failure.getMessage().contains("Restore the original applied migration"));
        assertNull(failure.getCause());
        assertEquals(1, messages.size());
        assertTrue(messages.getFirst().contains("CHECKSUM_MISMATCH, version=004"));
        assertFalse((failure.getMessage() + messages).contains("DO_NOT_LOG"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulValidationDetailRequiresDebug(boolean debug) throws Exception {
        diagnostics(debug).validation(result(true, List.of()));

        assertEquals(debug ? 1 : 0, messages.size());
        if (debug) {
            assertTrue(messages.getFirst().contains("stage=migration.validation, checked=4"));
            assertFalse(messages.getFirst().contains("DO_NOT_LOG"));
        }
    }

    @Test
    void boundsValidationDetailsAndRejectsUnsafeMetadata() {
        var errors = new ArrayList<ValidateOutput>();
        for (int index = 0; index < 20; index++) {
            errors.add(new ValidateOutput("DO_NOT_LOG\nSQL", "DO_NOT_LOG", "DO_NOT_LOG",
                    new ErrorDetails(UNTRUSTED_CODE, "DO_NOT_LOG")));
        }

        DatabaseException failure = assertThrows(DatabaseException.class,
                () -> diagnostics(true).validation(result(false, errors)));

        assertEquals(9, messages.size());
        assertTrue(failure.getMessage().contains("VALIDATE_ERROR, version=unavailable"));
        assertFalse((failure.getMessage() + messages).contains("DO_NOT_LOG"));
    }

    @Test
    void summarizesKnownFlywayFailureAndSqlStateWithoutExceptionText() {
        var external = new FlywayException("DO_NOT_LOG",
                new SQLException("DO_NOT_LOG_SQL", "P0001"), CoreErrorCode.DUPLICATE_VERSIONED_MIGRATION);

        DatabaseException failure = DatabaseException.failure("migration", external);

        assertTrue(failure.getMessage().contains("SQLSTATE P0001"));
        assertTrue(failure.getMessage().contains("reason=DUPLICATE_VERSIONED_MIGRATION"));
        assertTrue(failure.getMessage().contains("duplicate migration versions"));
        assertFalse(failure.getMessage().contains("DO_NOT_LOG"));
        assertNull(failure.getCause());
    }

    @Test
    void rejectsUntrustedErrorCodeAndSqlState() {
        var external = new FlywayException("DO_NOT_LOG", new SQLException("DO_NOT_LOG", "DO_NOT_LOG"),
                UNTRUSTED_CODE);

        DatabaseException failure = DatabaseException.failure("migration", external);

        assertEquals("Database migration failed.", failure.getMessage());
    }
}
