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
    public void discardConnection(Connection connection) throws SQLException {
        // Evict while the handle is checked out: Hikari closes the physical connection.
        // Do not recycle the invalid handle through close/reset after its pool entry is gone.
        pool.evictConnection(connection);
    }

    @Override
    public void close() {
        closePool(pool);
    }
}
