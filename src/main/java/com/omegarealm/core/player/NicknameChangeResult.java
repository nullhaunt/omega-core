package com.omegarealm.core.player;

import java.util.Objects;

public record NicknameChangeResult(Status status, PlayerNickname current) {

    public NicknameChangeResult {
        Objects.requireNonNull(status, "status");
        if ((status == Status.NOT_FOUND) != (current == null)) {
            throw new IllegalArgumentException("Only a missing player has no nickname state.");
        }
    }

    public enum Status {
        CHANGED, UNCHANGED, INVALID, RESERVED, TAKEN, STALE, NOT_FOUND
    }
}
