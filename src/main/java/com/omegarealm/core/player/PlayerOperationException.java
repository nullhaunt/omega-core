package com.omegarealm.core.player;

/**
 * Safe application result; never includes a database exception chain or input values.
 */
public final class PlayerOperationException extends Exception {

    private final Kind kind;

    PlayerOperationException(Kind kind) {
        super("Player operation " + kind.name().toLowerCase(java.util.Locale.ROOT) + ".");
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public enum Kind {
        UNAVAILABLE,
        BUSY,
        DUPLICATE,
        CANCELLED,
        TIMED_OUT,
        NOT_PREPARED
    }
}
