package com.omegarealm.core.database;

import com.zaxxer.hikari.SQLExceptionOverride;

import java.sql.SQLException;

/**
 * Hikari's automatic eviction logs the original driver exception. Application owners
 * instead report controlled phase/SQLSTATE diagnostics and explicitly discard failed
 * connections (or close the entire pool after failed bootstrap).
 */
final class ControlledSqlExceptions implements SQLExceptionOverride {

    @java.lang.Override
    public SQLExceptionOverride.Override adjudicate(SQLException failure) {
        return SQLExceptionOverride.Override.DO_NOT_EVICT;
    }
}
