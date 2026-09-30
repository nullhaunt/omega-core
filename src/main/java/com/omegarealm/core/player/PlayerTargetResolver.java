package com.omegarealm.core.player;

import java.util.concurrent.CompletionStage;

/**
 * Reusable, nonblocking UUID/current username/current nickname lookup.
 * A found result fixes the target UUID; callers must not resolve its alias again when
 * performing a mutation. Historical reservations are not command aliases.
 */
public interface PlayerTargetResolver {

    CompletionStage<PlayerTargetResolution> resolveTarget(PlayerTarget target);
}
