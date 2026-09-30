package com.omegarealm.core.player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Blocking profile operations on the existing repository owner's connection and I/O thread.
 * This view owns no resources. Account-name reservation is part of the owner's join transaction.
 */
public interface PlayerProfileRepository {

    Optional<PlayerNickname> loadNickname(UUID playerUuid) throws PersistenceException;

    NicknameChangeResult changeNickname(NicknameChange change) throws PersistenceException;

    /**
     * At most 64 notices, oldest first; a read does not acknowledge delivery.
     */
    List<NicknameNotice> pendingNicknameNotices(UUID playerUuid) throws PersistenceException;

    /**
     * Call only after a private delivery succeeds; another player's notice cannot be acknowledged.
     */
    boolean acknowledgeNicknameNotice(UUID playerUuid, UUID noticeId) throws PersistenceException;
}
