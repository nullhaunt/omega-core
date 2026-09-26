package com.omegarealm.core.database;

import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

final class PostgresDatabase implements Database {

    private final HikariDataSource pool;

    PostgresDatabase(HikariDataSource pool) {
        this.pool = pool;
    }

    static void closePool(HikariDataSource pool) {
        // Cancellation must not short-circuit Hikari's bounded shutdown waits.
        boolean interrupted = Thread.interrupted();
        try {
            pool.close();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public Connection openConnection() throws SQLException {
        return pool.getConnection();
    }

    @Override
    public void close() {
        closePool(pool);
    }
}
