package com.omegarealm.core.player;

import com.omegarealm.core.database.Database;

/**
 * Acquires a repository on the runtime's I/O worker and transfers ownership to its caller.
 */
@FunctionalInterface
public interface PlayerRepositoryFactory {

    PlayerSessionRepository acquire(Database database, String serverId) throws PersistenceException;
}
