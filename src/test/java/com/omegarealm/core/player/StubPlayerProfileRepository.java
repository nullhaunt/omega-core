package com.omegarealm.core.player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Unused profile paths fail loudly in fixtures for other gates.
 */
public class StubPlayerProfileRepository implements PlayerProfileRepository {

    @Override
    public Optional<PlayerNickname> loadNickname(UUID uuid) throws PersistenceException {
        throw new AssertionError("Unexpected profile read.");
    }

    @Override
    public NicknameChangeResult changeNickname(NicknameChange change) throws PersistenceException {
        throw new AssertionError("Unexpected nickname change.");
    }

    @Override
    public List<NicknameNotice> pendingNicknameNotices(UUID uuid) throws PersistenceException {
        throw new AssertionError("Unexpected notice read.");
    }

    @Override
    public boolean acknowledgeNicknameNotice(UUID uuid, UUID id) throws PersistenceException {
        throw new AssertionError("Unexpected notice acknowledgement.");
    }
}
