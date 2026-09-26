package com.omegarealm.core.database;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Owned database resource. Connection checkout and all SQL must execute off Minecraft threads.
 */
public interface Database extends AutoCloseable {

    Connection openConnection() throws SQLException;

    /**
     * Permanently discard a failed checkout. Pools must evict instead of recycling it.
     */
    default void discardConnection(Connection connection) throws SQLException {
        connection.close();
    }

    @Override
    void close();
}
