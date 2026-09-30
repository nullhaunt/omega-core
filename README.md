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

The first authorization gate supplies a platform-independent role/capability policy.
Players have one role in the `PLAYER`, `MODERATOR`, `ADMIN`, `OWNER` hierarchy. Admins may
manage moderator roles; owners may also manage admin roles; owner changes require the local
console. Peer/superior targets and self-changes are protected. The
[authorization contract](docs/AUTHORIZATION.md) records the policy and remaining gates.
Role persistence now supplies one realm-wide role/revision record per persisted player UUID.
Preparation reads profile and authorization together before world entry; a first successful
join atomically creates the default `PLAYER` record with player/session history. Reconnect
does not reset elevated roles or revisions. The player service exposes snapshots by exact
connection token and closes access on disconnect, failure or stop. Missing or invalid authority
for an existing player fails closed rather than silently granting a default role.
Local-console role changes now atomically replace the role, increment its revision and
record actor, target, previous/new roles, reason, server and timestamp in `role_change_audit`.
The writer rechecks stored actor/target authority while holding row locks; supplied player
roles never establish authority. Missing targets and denied requests leave history unchanged.
An authorized request for the existing role creates neither a revision nor an audit record.

Use the actual local console, or an authenticated ADMIN/OWNER player:

```text
role set <username|nickname|UUID> <PLAYER|MODERATOR|ADMIN|OWNER> <reason...>
```

The target must have a persisted player identity; offline targets are supported. A name
matches the player's current stored username or nickname exactly, ignoring ASCII case.
Historical usernames, former nicknames, partial names and players who have never joined
do not resolve. Matching both names of one UUID counts once; matches to different UUIDs
are ambiguous. Use a canonical UUID when a name is ambiguous. No external identity
service is queried. Online names are suggested as the target is typed; offline names and
UUIDs can still be entered directly. `/role set` and `/profile` advertise a player-profile
target to clients so Bedrock can offer online player names. Command execution still uses
Omega's persisted lookup; it does not resolve identities through Paper or Mojang.
Suggestions do not bypass the persisted lookup.
UUIDs remain the identity used for role writes and audits. Role names
accept either case; a reason of 1-512 characters is required. Command execution queues
the target lookup and any resulting role write on the existing I/O worker without blocking
a tick thread. The immediate reply acknowledges the lookup queue; a successful final reply
names the player and their new role in plain language. Revision and audit IDs remain in the
durable audit history; operational logs retain the operation and outcome. Routine command
feedback omits those internal identifiers. ADMIN can assign PLAYER/MODERATOR;
OWNER can also assign ADMIN. Only local console can change OWNER. RCON, command blocks,
delegated execution, self-changes and peer/superior targets cannot grant Omega authority.
`/help` lists commands visible to the sender; `/help <command>` shows that command's
description and syntax when the sender has access. Help does not repeat the online-player
or assignable-role suggestions exposed by tab completion. The registered namespaced paths
`omegacore:role` and `omegacore:help` share those checks. The old `/omega role set` form has
been retired. Native management roots are summarized in ADMIN/OWNER help output.

Connected players can use `/profile` for their own read-only profile or
`/profile <current-username|nickname|UUID>` for a persisted player. The dialog currently shows the
stored username, first successful join date in UTC, and the current stored staff role for
Moderator, Admin, or Owner. Players with the ordinary Player role have no staff label.
The role, identity and optional nickname come from one stored snapshot; missing or invalid
authorization or nickname state fails closed for a uniquely matched player. Nickname
rendering in the dialog belongs to the later presentation gate.
The dialog does not invent Realm, Frontier, Slayer or achievement data. Lookup uses the
existing bounded player worker and never blocks the command thread. A missing or ambiguous
name opens no dialog. Current names include the
default Floodgate prefix, such as `.Unreference`; Java and Bedrock identities remain separate
unless an explicit account-linking feature is added. The same Paper dialog is sent to Java
and translated by Geyser for Bedrock. `omegacore:profile` is the namespaced path; local
console and delegated senders cannot open a profile dialog. Online names appear as target
suggestions; offline current names, current nicknames and UUIDs remain valid when entered directly.
The public profile uses text and buttons on both editions. Player heads and portraits
are outside v0.2; external portrait APIs and self-hosted head images are not planned.

The nickname persistence gate adds `V008` and a focused `PlayerProfileRepository` view
of the existing session owner's connection. UUID-based nickname reads, revision-checked
self-edits, pending conflict notices, and owner-specific notice acknowledgement run on
the existing bounded player worker. Nicknames accept 3-16 ASCII letters, digits, and
underscores; PostgreSQL enforces case-insensitive uniqueness. Account-name reservations
retain recorded username history and protect both Floodgate's `.Name` and `Name` spelling.
A successful account join clears another player's conflicting nickname and commits its
audit and pending notice with the session. A changed or cleared nickname is reusable
unless an account reservation also protects it. Claims and account reservations share
a transaction-scoped database lock across server IDs, with the existing query bounds.
Existing migration files remain unchanged; `V008` seeds only currently recorded names.

The shared resolver now accepts current nicknames through the same bounded worker and
database read as account names and UUIDs. Historical account-name reservations and
Floodgate's unprefixed reservation do not create targeting aliases. Once resolved, a
mutation retains the UUID even if the nickname changes or is reused. `/role` and `/profile`
already consume this resolver; their suggestions and help still use account names until
the commands gate. No additional migration or alias cache is needed.

`/nickname`, `/realname`, nickname suggestions, chat/profile presentation, editing dialogs,
and private notification delivery remain in later gates. The stored notice
remains pending until a later Paper adapter confirms delivery and queues acknowledgement.
No extra pool, worker, timer, or external identity API is introduced. The
[nickname contract](docs/NICKNAMES.md) records the gate boundaries and recovery behavior.

Omega handles public player chat through a server-owned delivery path. It cancels Paper's
normal signed broadcast and sends a fresh Adventure component to the viewers retained by
the chat event, including the console when it is a viewer. The message shows the current
online name and the final plain text from the event, so formatting and interactive text
do not become part of public chat. A chat event already cancelled by another listener
remains silent. Chat is dropped unless Omega is `READY`; an unexpected delivery failure
fails the runtime and requests server shutdown. This path does not forward the incoming
player signature to Java viewers. Omega supplies the same component to Java and to
Geyser-backed Bedrock viewers. It does not add a mute, player-report or review workflow
yet; those server-side moderation rules need their own gate. Other plugins or future
private-chat features must follow the same delivery rule to avoid signed player chat.
Account, skin and server reporting outside this public-chat path remains possible.
See Paper's [chat event](https://docs.papermc.io/paper/dev/chat-events/) and
[signed message](https://docs.papermc.io/paper/dev/component-api/signed-messages/) contracts.
Java 25 unit tests and shaded packaging passed for this change. A disposable Paper 26.3
build-49 run accepted an inbound protocol chat message, delivered it to the client as a
system-chat packet, logged it to the console and shut down cleanly. Java graphical-client
report UI and live Geyser/Bedrock rendering have not been checked for this build.

`/build` and `/status` are standalone management diagnostics for fresh ADMIN/OWNER players
and the actual local console. RCON, delegated execution and lower roles cannot use them;
`/help` hides them from those players. `/build` reports the shaded artifact's version,
abbreviated Git revision and build time in UTC. `/status` reports runtime state,
server/environment, runtime age, tracked connections and open sessions, pending player I/O,
the age of the last successful database connection and server ID lock check, and the oldest
connected authorization observation. An uninitialized service or absent observation is
reported as such. These values come from bounded in-memory state. The command never waits
for PostgreSQL or performs a live health query. The database line records the last time
the retained connection completed a query and confirmed the exclusive server ID lock.
It does not promise current database availability. Each command sends one grouped reply
with readable times and labels. Player replies color important states; console output remains plain.
Both commands log an operational `MANAGEMENT` invocation. They remain readable when the
durable command-audit queue is unavailable because they do not change server state.
Their namespaced paths are `omegacore:build` and `omegacore:status`.

When Floodgate is installed, Omega recovers Java-visible Bedrock skins left on Floodgate's
signed Steve placeholder. Install the same shaded Omega JAR in Paper's `plugins/` and
Geyser Standalone's `extensions/` directories. Its Geyser entry point sends hashes of the
current 64x64 classic Bedrock skin to Paper after the Java connection is ready. It sends no
skin image bytes. Omega requests Geyser Global API's signed conversion asynchronously and
applies it only when the API skin hash matches the current client's skin. If conversion is
still pending, Omega schedules up to eight further checks over about eight minutes while the
same player remains connected. Each request uses a fresh cache key because the public API can
continue serving an older conversion after an upload. A prior conversion cannot replace a
newly selected Bedrock skin. A matching conversion can replace an older signed texture on the
Paper profile. Persona and other skin sizes are not covered by this bridge. There are at most
32 concurrent lookups, with an eight-second HTTP deadline and a 32 KiB response limit. Quit
and shutdown cancel pending lookups and retry tasks; HTTP failure leaves the existing skin in
place.
Floodgate is optional for Java-only servers, and no third-party skin plugin or configuration
key is added.

An isolated Paper 26.3 build-135, Geyser Standalone 2.11.3-b1247 and Floodgate run with real
Java and Bedrock clients confirmed the initial classic skin and a changed classic skin after
the matching signed conversion became readable. The changed skin took about a minute to
reach Java in that run; timing depends on Geyser's conversion service. Bedrock saw its new
skin sooner. Live deployment, other skin formats, Folia and production load remain unverified.

Skin recovery starts automatically when the Bedrock player joins. Rejoining starts a fresh
check. Omega cannot force Geyser's conversion to complete, so it does not expose a manual skin
refresh command. The bridge does not have an offline player's current Bedrock skin.

Authorized staff receive usage guidance for incomplete commands and specific target, role and
reason validation messages. Missing or ambiguous names have distinct replies. Self-changes
explain the local-console requirement; other policy denials explain that no role change was
made. Command access remains hidden from non-staff.
Omega replies use `Ω |` with player-side information, success and error colors. Help and
role outcomes group related lines into one message. An accepted target lookup immediately
says the player is being checked; the final reply follows database completion. This first
message is not proof of a committed change.
If completion is uncertain, inspect audit history before retrying. Changes remain durable if
shutdown wins after commit.

Connected authorization is checked every second on the existing database worker, in batches
of at most 64 identities with a 2-second refresh-query timeout. Snapshots expire five seconds
after the start of their database observation. Expiry immediately denies snapshot access and
requires relog; Paper reconciliation schedules the kick on the exact player's entity context.
A delayed read cannot renew an expired connection, replace a newer revision or affect a
reconnect. Role writes retain the existing 15-second mutation deadline; database/ownership
failures still request whole-server shutdown. Polling and expiry use the existing owned clock.

Players now receive an explicit default-deny Bukkit attachment, including permissions which
normally default to true. Players remain non-OP, persisted OP entries are cleared before
admission, and the native OP command is disabled. ADMIN/OWNER receive the reviewed native
management catalog below; PLAYER/MODERATOR receive no native command grants in this gate.
Omega's custom gameplay commands will supply their own capability checks in subsequent gates.

| Tools | ADMIN/OWNER grants |
| --- | --- |
| Server diagnostics | version/ver/about, plugins/pl, tps, mspt |
| Paper | chunkinfo, debug, entity, fixlight, heap, holderinfo, mobcaps, playermobcaps, dumpplugins, dumplisteners, version/ver |
| spark | health/healthreport, profiler/sampler, tps, ping, gc, gcmonitor, tickmonitor, heapsummary, heapdump, activity |
| World settings | difficulty, gamerule, time, weather, worldborder, defaultgamemode, setworldspawn |
| Maintenance | whitelist, save-all, setidletimeout, forceload |

No parent or wildcard permissions are granted. Native OP, reload, stop/restart, save-off/on,
moderation, automation and gameplay commands remain unavailable in-game. Maintenance includes
whitelist's own reload operation; this does not grant server/datapack reload. Native tools
retain their existing handlers and effects, including diagnostic exports and maintenance I/O.
They run in the requesting player's context. Local console retains its native management tools,
except OP and server/datapack reload. Reload rebuilds the dispatcher and is incompatible with
this enforcement boundary; use a full restart. Omega has no controlled shutdown/restart command yet.

Denied commands and Paper/spark branches are absent from client trees and server suggestions,
and fail through Minecraft's unknown/incomplete command parser. Existing vanilla argument types
are preserved. Execution rechecks exact, fresh connected authority even after parsing. Clickable
sign command execution is disabled because Paper elevates its native permissions. Authorized
native management invocations log actor UUID, role, server, canonical command path, category
and invocation outcome. Significant native commands run by an authorized player or the local
console also enqueue an argument-free `INVOKED` row in `command_audit` before execution.
Reviewed native commands are captured at their handler; other console commands are captured
at Paper's command event, so a parser rejection can still have an invocation row. A full or
unavailable audit queue denies execution, except that console/RCON `stop` remains available
with an operational log when persistence is down. Accepted rows use the existing
ordered I/O worker and are drained on orderly shutdown; a crash or persistence failure between
native execution and the SQL commit can still leave an invocation without a database row.
`INVOKED` does not claim
the native effect succeeded. `MANAGEMENT` and `STAFF` are separate categories for significant
commands in both the operational log and `command_audit`. Future staff/management handlers
must use the same audit path; no staff command handlers exist yet. RCON native invocations
are marked `UNTRUSTED` rather than gaining local-console authority. Everyday player commands
and command blocks are outside this staff/management audit boundary.

Accepted `/role set` mutations write a `QUEUED` row when the existing database worker begins
processing them, after target resolution. Their final `CHANGED`, `UNCHANGED`, `DENIED` or
`NOT_FOUND` row uses the same operation ID. A `CHANGED` row links the existing
`role_change_audit` record and commits in the
same transaction as the role and revision. If either audit write fails, the role does not
change and Omega closes admission; a `QUEUED` row may remain without a final row when the
outcome is uncertain. The immediate lookup-queued message means only the lookup entered the
worker queue; a missing or ambiguous name has no durable role-request row. Invalid input,
self-denials and requests rejected before mutation acceptance remain in the operational log
only. The table records the target UUID and normalized requested role. Raw command arguments
and reasons are absent from
`command_audit`; the required reason remains in the detailed
`role_change_audit`. The table is append-only through Omega, with no automatic purge yet.
Unlike `command_audit`, `role_change_audit` records only committed role transitions, including
the previous/new role, revision and required reason. Other significant commands such as native
world settings have no `role_change_audit` row.

One attachment is owned by each exact connection. Reconciliation observes changes every 250 ms
and applies them on the captured entity scheduler, then refreshes the client tree. Authority
is denied while its attachment revision differs from the fresh snapshot. An application delay
of five seconds becomes terminal and requires reconnect; the kick waits for the owning thread.
Database freshness expiry keeps its existing independent five-second deadline.

The native integration is confined to `NativeCommandFence`, using Paper's native dispatcher because
the public API exposes opaque vanilla handlers. The dev bundle follows 26.3 development builds;
runtime startup accepts any Paper 26.3 build, including unidentified local builds. Build numbers
are not compatibility gates. An actual command API linkage or enforcement setup failure still
closes admission and stops the server. Other Minecraft versions/brands are outside this target.
Disposable runtime verification covers builds 42 and 49; this is evidence, not a build allowlist.
This enforcement is installed at server load before runtime bootstrap starts and
can publish READY. New commands belong in the normal command-registration lifecycle. Other
Omega plugins must use application authority, not attachments or OP as credentials; arbitrary
plugins running in the same JVM are trusted code, not sandboxed by this permission boundary.
General gameplay scheduling, controlled shutdown/restart,
durable native command coverage, backup/rollback, production load and Folia remain separate gates.

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
- `debug`: YAML boolean. Enables controlled startup stages, elapsed timings, migration
  progress and connection-preparation timings. Each accepted load reports its mailbox
  queue wait and repository transaction duration; the async Paper configuration handler
  reports how long it waited for preparation. Times are monotonic milliseconds with three
  decimal places. The repository duration includes the ownership check, profile/role read
  and commit. These lines contain only stage, duration and controlled outcome, not player
  identity or connection address. They begin after Minecraft online authentication, so
  they cannot time Mojang's session service. Known failure reasons and migration versions
  are reported even with debug off. Debug never enables raw SQL, credentials or third-party
  exception logging.

For a diagnostic pass, set `debug: true` in the operator configuration and restart using
the rebuilt shaded JAR. Check the reported startup stage, SQLSTATE, migration reason and
version before changing database state. A `CHECKSUM_MISMATCH` means the applied script and
artifact differ; restore the original script instead of repairing history. Applied scripts
must remain unchanged, including SQL whitespace and comments.

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

`test` runs configuration, admission, lifecycle, authorization policy, player-service and
Paper adapter tests.
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

The development `runServer` task starts Paper and the existing standalone Geyser bridge
together. Keep exactly one Floodgate, ViaVersion and ViaBackwards JAR in `run/plugins/`.
Keep `Geyser-Standalone.jar`, its configured `config.yml` and Floodgate `key.pem` in
`run/geyser-standalone/`; the matching Floodgate key must also be present in
`run/plugins/floodgate/`. These files are local operator data and are not downloaded or
replaced by Gradle. `runServer` copies Omega's shaded JAR to
`run/geyser-standalone/extensions/OmegaCoreSkinBridge.jar` before starting Geyser. For a
manual deployment, copy that same JAR to both locations before restarting Paper and Geyser.
Start `runServer` from the shell that supplies Omega's database
password environment variable. Paper keeps its 8 GB JVM settings; Geyser has a 1 GB
maximum heap and writes output to `run/geyser-standalone/gradle-geyser.log`.
When Paper exits, the task sends Geyser its stop command and then ends any bridge
process it started that does not stop promptly. Stop a separately launched Geyser
instance before using `runServer` so the Bedrock port is free.
