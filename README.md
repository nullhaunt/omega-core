# Omega Core

Omega Realm's server foundation, targeting Paper 26.3 and Java 25.
One Gradle project produces one deployable plugin. No other Minecraft plugins are required.

## Current slice

`OmegaPlugin` adapts Paper lifecycle events to `OmegaRuntime`. Configuration is validated
during bootstrap, then a dedicated worker initializes PostgreSQL, applies versioned migrations
and checks connectivity. The runtime publishes `READY` through Paper's global scheduler.
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

This slice creates the player and session tables; it does not yet load players or record
sessions. Repositories, player services, commands and the full gameplay scheduler come later.
Readiness currently covers the database foundation. Future critical services must join the
startup sequence before they can accept players.

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

`test` runs configuration, admission and lifecycle tests. `build` also runs `integrationTest`
against an isolated PostgreSQL 18.6 instance, verifying migrations, rollback, checksum
validation and pool cleanup. Native test binaries download through Gradle on the first run;
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

The repository's `runServer` task retains its existing development JVM settings. Review
those settings before using it on a machine with limited available memory.
