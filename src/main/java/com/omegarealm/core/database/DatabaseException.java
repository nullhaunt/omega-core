package com.omegarealm.core.database;

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
        Throwable current = failure;

        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof SQLException sqlException && sqlException.getSQLState() != null
                    && sqlException.getSQLState().matches("[A-Z0-9]{5}")) {
                sqlState = sqlException.getSQLState();
            }

            current = current.getCause();
        }

        String detail = sqlState == null ? "" : " (SQLSTATE " + sqlState + ")";
        return new DatabaseException("Database " + phase + " failed" + detail + ".");
    }
}
