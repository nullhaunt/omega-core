package com.omegarealm.core.player;

import com.omegarealm.core.authorization.Role;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable public projection composed from one stored identity/role/nickname snapshot.
 * Editing revisions remain in the focused profile boundary, not this public view.
 */
public record PublicPlayerProfile(PlayerProfile identity, Role role, Optional<String> nickname) {

    public PublicPlayerProfile {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(nickname, "nickname");
        if (nickname.isPresent() && (!NicknamePolicy.valid(nickname.get()) || NicknamePolicy.reserved(nickname.get()))) {
            throw new IllegalArgumentException("Invalid public nickname.");
        }
    }

    public PublicPlayerProfile(PlayerProfile identity, Role role) {
        this(identity, role, Optional.empty());
    }

    public Optional<String> staffDesignation() {
        return switch (role) {
            case PLAYER -> Optional.empty();
            case MODERATOR -> Optional.of("Moderator");
            case ADMIN -> Optional.of("Admin");
            case OWNER -> Optional.of("Owner");
        };
    }
}
