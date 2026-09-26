package com.omegarealm.core.database;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Owned database resource. Connection checkout and all SQL must execute off Minecraft threads.
 */
public interface Database extends AutoCloseable {

    Connection openConnection() throws SQLException;

    @Override
    void close();
}
