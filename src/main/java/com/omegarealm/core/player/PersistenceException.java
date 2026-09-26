package com.omegarealm.core.player;

import java.sql.SQLException;

/**
 * Controlled diagnostics without external exception messages or secret-bearing causes.
 */
public final class PersistenceException extends Exception {

    private final Kind kind;

    PersistenceException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    static PersistenceException database(String phase, SQLException failure) {
        String sqlState = failure.getSQLState();
        String detail = sqlState != null && sqlState.matches("[A-Z0-9]{5}")
                ? " (SQLSTATE " + sqlState + ")" : "";
        return new PersistenceException(Kind.DATABASE_FAILURE,
                "Player persistence " + phase + " failed" + detail + ".");
    }

    public Kind kind() {
        return kind;
    }

    public enum Kind {
        SERVER_ALREADY_OWNED,
        OWNERSHIP_LOST,
        SESSION_CONFLICT,
        INVALID_SESSION_END,
        CANCELLED,
        DATABASE_FAILURE
    }
}
