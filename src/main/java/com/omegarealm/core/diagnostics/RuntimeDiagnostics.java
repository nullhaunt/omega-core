package com.omegarealm.core.diagnostics;

import com.omegarealm.core.RuntimeState;
import com.omegarealm.core.player.PlayerServiceDiagnostics;

import java.time.Duration;
import java.util.Optional;

/**
 * A read-only runtime snapshot; it performs no database or Paper access.
 */
public record RuntimeDiagnostics(RuntimeState state, String serverId, String environment,
                                 Duration runtimeAge, Optional<PlayerServiceDiagnostics> players) {
}
