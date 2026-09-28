package com.omegarealm.core.player;

import java.util.concurrent.CompletionStage;

/**
 * Reusable, nonblocking target lookup for commands. Callers never query names themselves.
 */
public interface PlayerTargetResolver {

    CompletionStage<PlayerTargetResolution> resolveTarget(PlayerTarget target);
}
