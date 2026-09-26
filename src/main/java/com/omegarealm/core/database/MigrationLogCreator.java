package com.omegarealm.core.database;

import org.flywaydb.core.api.logging.Log;
import org.flywaydb.core.api.logging.LogCreator;

/**
 * Flyway's public logging extension deliberately suppresses raw SQL and exception text.
 * PostgresBootstrap emits the safe migration summary and phase/SQLSTATE errors instead.
 * Flyway constructs this class by name, so it must remain public with a no-argument constructor.
 */
public final class MigrationLogCreator implements LogCreator {

    private static final Log SILENT = new SilentLog();

    @Override
    public Log createLogger(Class<?> type) {
        return SILENT;
    }

    private static final class SilentLog implements Log {

        @Override
        public void debug(String message) {
        }

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }

        @Override
        public void error(String message) {
        }

        @Override
        public void error(String message, Exception failure) {
        }

        @Override
        public void notice(String message) {
        }
    }
}
