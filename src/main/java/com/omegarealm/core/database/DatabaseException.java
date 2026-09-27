package com.omegarealm.core.database;

import org.flywaydb.core.api.CoreErrorCode;
import org.flywaydb.core.api.FlywayException;

import java.sql.SQLException;

/**
 * Safe operator-facing bootstrap error, deliberately excluding external exception messages.
 */
public final class DatabaseException extends Exception {

    public DatabaseException(String message) {
        super(message);
    }

    static DatabaseException failure(String phase, Throwable failure) {
        String sqlState = null;
        CoreErrorCode migrationCode = null;
        Throwable current = failure;

        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof SQLException sqlException && sqlException.getSQLState() != null
                    && sqlException.getSQLState().matches("[A-Z0-9]{5}")) {
                sqlState = sqlException.getSQLState();
            }
            if (current instanceof FlywayException flyway
                    && flyway.getErrorCode() instanceof CoreErrorCode code) {
                migrationCode = code;
            }

            current = current.getCause();
        }

        String detail = sqlState == null ? "" : " (SQLSTATE " + sqlState + ")";
        String reason = migrationCode == null ? "" : " [reason=" + migrationCode.name() + "]";
        String guidance = migrationCode == null ? "" : guidance(migrationCode);
        return new DatabaseException("Database " + phase + " failed" + detail + reason + "." + guidance);
    }

    static String guidance(CoreErrorCode code) {
        return switch (code) {
            case CHECKSUM_MISMATCH, DESCRIPTION_MISMATCH, TYPE_MISMATCH ->
                    " Restore the original applied migration or deploy the artifact matching this database's history."
                            + " Do not edit history or run automatic repair.";
            case APPLIED_VERSIONED_MIGRATION_NOT_RESOLVED, APPLIED_REPEATABLE_MIGRATION_NOT_RESOLVED ->
                    " Deploy the artifact containing the applied migration; verify packaged migration resources.";
            case DUPLICATE_VERSIONED_MIGRATION, DUPLICATE_REPEATABLE_MIGRATION, DUPLICATE_UNDO_MIGRATION,
                 DUPLICATE_DELETED_MIGRATION -> " Check the artifact for duplicate migration versions.";
            case NON_EMPTY_SCHEMA_WITHOUT_SCHEMA_HISTORY_TABLE ->
                    " Use the intended managed database; an unmanaged nonempty schema cannot be adopted automatically.";
            case FAILED_BASELINE_MIGRATION, FAILED_VERSIONED_MIGRATION, FAILED_REPEATABLE_MIGRATION ->
                    " Inspect the failed migration and database state before an explicitly reviewed recovery.";
            case VALIDATE_ERROR -> " See the migration validation reason and version reported above.";
            default -> "";
        };
    }
}
