package com.omegarealm.core.database;

/**
 * Transfers a fully initialized database to the caller; closes partial resources on failure.
 */
@FunctionalInterface
public interface DatabaseBootstrap {

    Database initialize() throws DatabaseException;
}
