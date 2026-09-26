package com.omegarealm.core.config;

/**
 * An operator-facing configuration failure. Messages must not contain input values.
 */
public final class ConfigException extends Exception {

    public ConfigException(String message) {
        super(message);
    }
}
