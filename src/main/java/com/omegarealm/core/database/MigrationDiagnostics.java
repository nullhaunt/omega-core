package com.omegarealm.core.database;

import org.flywaydb.core.api.CoreErrorCode;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.output.ValidateOutput;
import org.flywaydb.core.api.output.ValidateResult;

import java.util.logging.Logger;

/**
 * Bootstrap-worker-owned metadata diagnostics; never forwards Flyway log/error text.
 */
final class MigrationDiagnostics implements Callback {

    private final Logger logger;
    private final boolean debug;
    private String activeVersion;

    MigrationDiagnostics(Logger logger, boolean debug) {
        this.logger = logger;
        this.debug = debug;
    }

    static String safeVersion(String version) {
        return version != null && version.matches("[0-9]{1,9}(\\.[0-9]{1,9}){0,7}")
                ? version : "unavailable";
    }

    void validation(ValidateResult result) throws DatabaseException {
        if (result.validationSuccessful) {
            if (debug) {
                logger.info("Database bootstrap debug: stage=migration.validation, checked=" + result.validateCount + ".");
            }
            return;
        }

        String firstVersion = "unavailable";
        CoreErrorCode firstCode = result.errorDetails != null
                && result.errorDetails.errorCode instanceof CoreErrorCode known
                ? known : CoreErrorCode.VALIDATE_ERROR;
        int reported = 0;
        if (result.invalidMigrations != null) {
            for (ValidateOutput invalid : result.invalidMigrations) {
                if (reported >= 8) {
                    logger.severe("Migration validation: additional errors omitted; inspect the artifact and database history.");
                    break;
                }
                CoreErrorCode code = invalid.errorDetails != null
                        && invalid.errorDetails.errorCode instanceof CoreErrorCode known
                        ? known : CoreErrorCode.VALIDATE_ERROR;
                String version = safeVersion(invalid.version);
                if (reported == 0) {
                    firstCode = code;
                    firstVersion = version;
                }
                logger.severe("Migration validation: reason=" + code.name() + ", version=" + version + "."
                        + DatabaseException.guidance(code));
                reported++;
            }
        }
        throw new DatabaseException("Database migration failed: validation reason=" + firstCode.name()
                + ", version=" + firstVersion + "." + DatabaseException.guidance(firstCode));
    }

    String activeVersion() {
        return activeVersion;
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_EACH_MIGRATE || event == Event.AFTER_EACH_MIGRATE
                || event == Event.AFTER_EACH_MIGRATE_ERROR;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        if (event == Event.BEFORE_EACH_MIGRATE) {
            var migration = context.getMigrationInfo();
            activeVersion = safeVersion(migration == null || migration.getVersion() == null
                    ? null : migration.getVersion().toString());
            if (debug) {
                logger.info("Database bootstrap debug: stage=migration.begin, version=" + activeVersion + ".");
            }
        } else if (event == Event.AFTER_EACH_MIGRATE) {
            if (debug) {
                logger.info("Database bootstrap debug: stage=migration.complete, version=" + activeVersion + ".");
            }
            activeVersion = null;
        }
        // Keep the failed version for the controlled bootstrap error, even with debug off.
    }

    @Override
    public String getCallbackName() {
        return "omega-migration-diagnostics";
    }
}
