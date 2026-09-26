# Omega Core

Omega Realm's server foundation, targeting Paper 26.3 and Java 25.
One Gradle project produces one deployable plugin. No other Minecraft plugins are required.

## Current slice

`OmegaPlugin` adapts Paper lifecycle events to `OmegaRuntime`. The runtime loads validated,
immutable settings before being published to the plugin. A configuration failure logs a
controlled error and requests an orderly shutdown of the entire server. Unexpected startup
failures use the same policy. Shutdown is idempotent.

Omega Core is required infrastructure: the server must not continue accepting players
without it. Disabling Omega Core while the server is running also requests a server shutdown.
Normal server shutdown still closes the runtime without requesting another shutdown.
A whitelist does not establish application readiness and is not used as the failure boundary.
This policy covers failures handled by Omega's lifecycle adapter; deployment must still
ensure Omega Core is present and can be loaded by Paper in the first place.

Configuration file access happens only during plugin bootstrap, before gameplay services
are registered. Future database work must run asynchronously; no database services,
connections, migrations, player services, commands or schedulers exist in this slice.
The foundation startup message does not indicate full application readiness.

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
  the connection pool and operational sizing will be implemented in the database slice.
- `debug`: YAML boolean. Reserved for later diagnostic behavior.

Never store database passwords in configuration or Git. `OMEGA_DB_PASSWORD` is the default
variable name. Its value is deliberately neither read nor required until database startup
is implemented. Validation errors do not include input values or YAML source excerpts.

## Build and verification

Use a Java 25 JDK, with `JAVA_HOME` pointing to that installation:

```powershell
.\gradlew.bat test build
```

The tests cover configuration creation/preservation, validation, secret-safe errors and
idempotent runtime shutdown. Build success is separate from Paper server verification.

For a manual lifecycle check, deploy the built plugin to a disposable Paper 26.3 server:

1. Start with no plugin data directory. Confirm `config.yml` is created and the foundation
   startup message appears.
2. Restart with a custom server ID. Confirm it is used and the file remains unchanged.
3. Set `database.port` to a quoted string or break YAML syntax. Confirm the configuration
   error and server-shutdown explanation appear, no foundation startup message appears,
   and the Paper process exits without an operator issuing `stop`.
4. Restore valid settings and stop the server. Confirm the foundation shutdown message
   appears once.
5. In a disposable integration environment, have a test plugin disable Omega Core through
   Paper's plugin manager. Confirm the shutdown explanation appears, the runtime closes
   once, and the server process exits without an operator issuing `stop`.

The repository's `runServer` task retains its existing development JVM settings. Review
those settings before using it on a machine with limited available memory.
