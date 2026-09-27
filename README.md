# Omega Core

Omega Realm's server foundation, targeting Paper 26.3 and Java 25.
One Gradle project produces one deployable plugin. No other Minecraft plugins are required.

## Current slice

`OmegaPlugin` adapts Paper lifecycle events to `OmegaRuntime`. Configuration is validated
during bootstrap, then a dedicated worker initializes PostgreSQL, applies versioned migrations
and checks connectivity. The same worker acquires exclusive ownership of `server.id`,
recovers its interrupted sessions and starts the player service. The runtime publishes
`READY` through Paper's global scheduler only after all of those steps succeed.
Player admission stays closed until that handoff completes.

Configuration, credentials, connection, migration and startup-timeout failures request an
orderly shutdown of the entire server. Shutdown is idempotent and cannot be reversed by a
late startup completion. The [bootstrap contract](docs/BOOTSTRAP.md) documents state transitions,
resource ownership, timeout limits, dependency choices and acceptance criteria.

Omega Core is required infrastructure: the server must not continue accepting players
without it. Disabling Omega Core while the server is running also requests a server shutdown.
Normal server shutdown still closes the runtime without requesting another shutdown.
A whitelist does not establish application readiness and is not used as the failure boundary.
This policy covers failures handled by Omega's lifecycle adapter; deployment must still
ensure Omega Core is present and can be loaded by Paper in the first place.

The persistence slice now supplies immutable player/session values and a blocking PostgreSQL
repository. Profile reads have no side effects. A successful join atomically writes player
metadata and opens its session; an exact session ID closes it on quit or shutdown. UUID owns
identity, first-seen is preserved, and last-seen never moves backward. PostgreSQL enforces
one open session per player per server. Interrupted sessions retain an unknown leave time
and a separate recovery timestamp, so an outage is not recorded as playtime.

Acquire, recover, use and close the repository on one dedicated I/O worker. It retains one
pool connection and a PostgreSQL session advisory lock for `server.id`; a second owner is
rejected. Every operation verifies ownership and uses that same connection. Failed owners
cannot reconnect or resume writes. Close the repository before the database. Failed pooled
connections are explicitly evicted; Hikari's raw exception logging is suppressed in favor
of controlled phase/SQLSTATE diagnostics. SQL statements have a 5-second query timeout;
existing connection, socket, startup and shutdown bounds remain unchanged.

`PlayerService` now coordinates immutable connection attempts through a bounded mailbox.
Preparation reads a profile without writing history. Activation records an observed world
join, and disconnect closes that exact session after its pending activation. Reconnect uses
a new token, so old results and callbacks cannot replace or remove the new attempt.
Timers bound loading, prepared-state retention, mutations and periodic ownership checks.
Critical persistence failures, including idle ownership loss, close admission after `READY`
and request server shutdown. Cleanup drains observed joins and departures before releasing
ownership and closing the pool; unfinished sessions remain recoverable after a crash.

Paper connection handling now holds asynchronous configuration until player preparation
finishes. A later login validation reads readiness and the exact prepared connection without
I/O; it preserves bans and other restrictions. World join queues session activation, and quit
queues closure without waiting on a tick thread. Disconnect during loading, abandoned attempts
and rapid reconnect use exact tokens and connection handles, with bounded reconciliation.
Server shutdown drains active sessions with `SHUTDOWN` rather than inventing a normal quit.
The [player/session gate contract](docs/PLAYER_SESSIONS.md) records ownership, limits and
verification. A disposable Paper protocol client verified delayed preparation before world
entry, chunk delivery and live session recording. Visual-client, proxy, Folia and production
load behavior remain separate evidence boundaries.

`OmegaScheduler` now owns global lifecycle dispatch and asynchronous connection
reconciliation through Paper. Closing it rejects new work and cancels owned task handles;
unexpected callback failures close dispatch and request server shutdown. The dedicated
database worker remains the owner of ordered persistence work. The
[scheduler contract](docs/SCHEDULER.md) documents cancellation and failure behavior.
Commands, authorization and entity/region gameplay scheduling remain future gates.

## Configuration

The first startup copies the bundled `config.yml` into `plugins/OmegaCore/`. Existing files
are read as-is, without default merging or replacement. Every setting in the bundled file
is required. Unknown keys, incorrect YAML types and invalid values prevent startup.
Changes require a server restart; application hot reload is not supported.

- `server.id`: 1-64 ASCII letters, digits, underscores or hyphens, starting with a letter or digit.
- `server.environment`: a custom label of 1-32 lowercase ASCII letters, digits or hyphens,
  starting with a letter. Examples: `development`, `staging`, `production`.
- `database.host`, `database.database`, `database.username`: nonblank strings without
  surrounding whitespace or control characters.
- `database.port`: YAML integer from 1 through 65535.
- `database.password-env`: an environment-variable name, using ASCII letters, digits and
  underscores, starting with a letter or underscore. Store only the name here.
- `database.pool-size`: positive YAML integer, at most 2147483647. The default is 5;
  keep it small and tune based on measurements.
- `debug`: YAML boolean. Reserved for later diagnostic behavior.

Never store database passwords in configuration or Git. `OMEGA_DB_PASSWORD` is the default
variable name. Its value must be nonblank in the server process environment. The target
database and login role must already exist; the role needs permission to create and update
Omega's schema and migration history. Validation errors do not include input values or
YAML source excerpts.

## Build and verification

Use a Java 25 JDK, with `JAVA_HOME` pointing to that installation:

```powershell
.\gradlew.bat test build
```

`test` runs configuration, admission, lifecycle, player-service and Paper adapter tests.
`build` also runs `integrationTest`
against isolated PostgreSQL 18.6 instances, verifying migrations, rollback, checksum
validation, player/session transactions, ownership, recovery, runtime integration and cleanup.
Native test binaries download through Gradle on the first run;
no existing database or Docker installation is used. Build success remains separate from
Paper server verification.

Deploy `build/libs/OmegaCore-0.1.0-SNAPSHOT-all.jar`; it bundles the database libraries.
The plain JAR is not the deployable artifact. Provide the database password through your
server launcher environment before starting Paper.

For a manual lifecycle check, deploy the built plugin to a disposable Paper 26.3 server:

1. Start once to create `config.yml`, then configure an isolated development database and
   supply the password environment variable. Confirm `STARTING`, migration validation and
   `READY` appear in order. Login attempts during `STARTING` must be denied.
2. Restart with a custom server ID. Confirm it is used, the file remains unchanged, and
   zero migrations are applied.
3. Set `database.port` to a quoted string or break YAML syntax. Confirm the configuration
   error and server-shutdown explanation appear, no `READY` message appears,
   and the Paper process exits without an operator issuing `stop`.
4. Restore valid settings and stop the server. Confirm `STOPPED` appears once and there
   are no pool or bootstrap workers left running.
5. In a disposable integration environment, have a test plugin disable Omega Core through
   Paper's plugin manager. Confirm the shutdown explanation appears, the runtime closes
   once, and the server process exits without an operator issuing `stop`.
6. Remove the password variable or make PostgreSQL unreachable. Confirm admission remains
   closed and the server exits automatically with a controlled bootstrap failure.
7. With an isolated database, deliberately delay a profile read during connection configuration.
   Confirm there is no world join or session write until preparation completes. Disconnect
   during loading and confirm no session is created; reconnect, join and quit, then verify
   distinct session IDs and the exact close reasons. A critical read failure must close
   admission and stop the server. Never apply these fixtures to a live database.

The repository's `runServer` task retains its existing development JVM settings. Review
those settings before using it on a machine with limited available memory.
