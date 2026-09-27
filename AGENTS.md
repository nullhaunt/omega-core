# Working on Omega Core

Omega Core is Omega Realm's required server foundation. Maintain readable, organized,
production-minded code through small, explicitly scoped implementation gates.

## Start here

1. Read the user's current request and inspect `git status --short`. Preserve existing
   staged and unstaged work; do not reset, restage, or rewrite unrelated changes.
2. Read [README.md](README.md), [build.gradle.kts](build.gradle.kts), and the relevant
   source and tests. Inspect current code before relying on an earlier handoff.
3. If available, read `docs/BOOTSTRAP.md` for the detailed bootstrap contract and
   `docs/OMEGA_CORE_V0.1.md` for broader architectural intent. `docs/` is currently
   ignored and may be absent in another checkout. Essential rules are recorded here.
4. Explain the immediate scope, intended behavior, and acceptance criteria before
   editing. For concurrency or persistence work, establish ownership, thread context,
   cancellation, failure, and shutdown behavior before implementation.

The architecture draft is design input, not an instruction to implement every section
or copy its examples verbatim. Explicit user instructions take precedence. Resolve
routine implementation choices within the authorized scope; ask when a missing decision
materially affects behavior or safety. Finish the requested gate without expanding into
unrequested systems. Creating this file does not authorize the next implementation gate.

## Project shape

- Target Java 25 and Paper 26.3. Use the Gradle wrapper and Kotlin build script.
- Keep one Gradle project and one deployable plugin using conventional `plugin.yml`.
  No other Minecraft plugins are required. Implementation libraries are appropriate
  when they solve a concrete problem and have clear ownership and packaging.
- Use the existing root package `com.omegarealm.core`.
- Keep `OmegaPlugin` a thin Paper lifecycle adapter. `OmegaRuntime` owns application
  startup, readiness, failure, and shutdown.
- `config/` owns strict loading and immutable configuration models; `database/` owns
  PostgreSQL bootstrap and connection resources; `lifecycle/` owns admission policy.
- SQL migrations belong in `src/main/resources/db/migration/`. Unit tests belong in
  `src/test/`; real PostgreSQL tests belong in `src/integrationTest/`.
- Keep domain models independent of Bukkit `Player`, world, and entity objects.
  Paper adapters translate platform events into application operations.
- Add abstractions for meaningful boundaries, not anticipated features. Do not introduce
  DI frameworks, extra modules, Redis, microservices, NMS, packet manipulation, or gameplay
  systems without an actual requirement. Keep dependency direction explicit.

## Lifecycle and safety contracts

- Runtime states are `STARTING`, `READY`, `FAILED`, `STOPPING`, and `STOPPED`.
  Only `READY` permits player admission. Readiness must cover every critical service
  introduced by subsequent gates, not merely the existence of a database connection.
- Register admission protection before configuration loading. Check both asynchronous
  pre-login and Paper's later connection validation. The guard reads state without I/O
  and preserves other admission restrictions. Whitelists and administrator exemptions
  do not establish application readiness.
- Critical startup failures close admission and request orderly shutdown of the entire
  server. Unexpected plugin disable while the server is running also requests shutdown.
  Normal server shutdown cleans up without requesting another shutdown.
- Deployment must independently ensure that Omega's JAR is present and loadable; code
  inside an absent or unloadable plugin cannot enforce the server's dependency on it.
- Shutdown is idempotent. Failure, timeout, or shutdown must win over late startup
  completion. Never publish `READY` after leaving `STARTING`.
- Define exactly one owner for every pool, executor, timer, and pending resource.
  Failed initialization closes partial resources; a result returned after cancellation
  must be closed instead of published. Do not rely on daemon threads instead of cleanup.
- State and ownership changes use a small synchronization boundary. Never perform
  database I/O, pool closure, or potentially blocking callbacks while holding it.
- Preserve bounded startup, connection, statement, lock, checkout, and cleanup waits.
  Cancel the startup deadline on readiness, failure, and stop. Document changes to limits.
- Publish bootstrap results through the appropriate Paper scheduler. Scheduler rejection
  must still close resources and request shutdown. The existing direct halt fallback
  only signals server shutdown; it performs no world or entity mutations.
- Blocking database, HTTP, and external filesystem I/O must not run on active Minecraft
  tick or region threads. Never block those threads on futures. Configuration loading
  during early bootstrap is a bounded exception; the existing bounded cleanup drain
  is permitted only at server shutdown, never during normal gameplay.
- Respect thread ownership when touching worlds or entities. Global scheduling does not
  make arbitrary entity access safe. Do not declare Folia support without a complete
  thread-ownership audit and actual Folia verification.

## Configuration, persistence, and diagnostics

- Create bundled configuration only when the operator file is absent. Preserve existing
  files without silent default merging or replacement. Configuration changes require
  restart; application hot reload is not supported.
- Parse YAML with exception-throwing `YamlConfiguration.loadFromString`. Do not use a
  convenience loader that logs malformed YAML and returns an empty configuration.
- Validate required keys, unknown keys, exact YAML types, ranges, and identifiers at
  the boundary. Do not coerce incorrect types or silently substitute defaults.
- Configuration records store the password environment-variable name, never its value.
  Resolve credentials on the bootstrap worker. Never commit credentials or print them.
- Keep operator errors controlled and useful: known setting names, failure phase, and
  SQLSTATE where appropriate. Do not expose input values, YAML excerpts, SQL source,
  raw driver/Flyway exception messages, or secret-bearing exception chains. Audit
  third-party logging as well as application logging.
- `debug` enables controlled diagnostic detail and requires restart. Use it during a
  diagnostic pass: report startup stages, elapsed time and validated migration versions.
  Known failure reasons, migration versions, SQLSTATE and actionable recovery guidance
  must remain available with debug off. Never make debug an exception to the credential,
  SQL-source or raw exception-chain restrictions above; do not enable raw Flyway/driver
  logging. Inspect the failing artifact and migration history before changing schema.
  Do not automatically repair, baseline, clean, alter applied migrations or restart a live
  server to gather diagnostics. Read-only database inspection should remain bounded.
- Use structured data-source configuration rather than concatenated JDBC URLs. Use
  prepared statements for data values and explicit transactions for atomic workflows.
  Connections and statements require deterministic closure.
- Flyway owns versioned migrations, history, validation, and migration locking. Applied
  migrations are immutable, including whitespace and comments; schema changes require a
  new versioned script. Restore the original script when accidental edits cause a checksum
  mismatch. Do not automatically baseline, repair, clean, or adopt an unmanaged nonempty schema.
- UUID is authoritative player identity. Usernames are mutable metadata. Session
  uniqueness and recovery need an explicit join/reconnect contract before implementation.
- Role changes require a reason and a durable audit in the same transaction. Recheck stored
  actor/target roles under locks acquired in UUID order; never trust a supplied role snapshot.
  The initial command is local-console-only and targets persisted UUIDs, including offline
  players. RCON, command blocks, players and delegated execution do not acquire console authority.
- Authorization polling uses the existing ordered worker and deadline clock: one-second
  interval, batches of at most 64, two-second refresh-query timeout and five-second freshness
  measured from before the database read. Access denies on expiry even if the timer is late.
  Expiry is terminal for the connection; late reads and reconnects cannot restore old authority.
  Schedule forced reconnect on the exact player's entity context. General Minecraft permission
  enforcement remains a separate gate; do not claim loaded/refreshed roles implement it.

## Code quality

- Follow surrounding formatting and naming. Prefer readable multi-line blocks and
  blank lines between logical stages. Avoid compressed control flow and unrelated
  formatting churn.
- Keep classes focused, methods understandable, and visibility as narrow as practical.
  Use immutable values for configuration and domain boundaries.
- Document non-obvious invariants, ownership, and reasons for constraints. Comments
  should explain decisions rather than narrate obvious statements.
- Make failure paths as deliberate as success paths. Do not swallow exceptions or add
  broad catch-and-continue behavior that leaves a partially initialized runtime usable.
- Propagate asynchronous failures intentionally. Preserve interruption semantics and
  resource cleanup during cancellation. A concurrent collection does not make a
  multi-step domain workflow atomic.
- Prefer established APIs and purposeful Java features over cleverness. Before changing
  version-sensitive Paper or library behavior, inspect the exact dependency and consult
  official documentation or source. Do not upgrade dependencies incidentally.

## Verification and delivery

Use a Java 25 JDK explicitly; the machine's default `java` may be older. The current
local installation is `D:\Dev\.jdks\ms-25.0.4.1`; verify it exists before using it.
Other machines should supply their own Java 25 installation.

```powershell
$env:JAVA_HOME = 'D:\Dev\.jdks\ms-25.0.4.1'
.\gradlew.bat test integrationTest build --console=plain
```

- `build` includes `integrationTest`. These tests launch isolated native PostgreSQL
  instances with temporary directories and ports; Docker and an existing database are
  not required. Initial dependency/native-binary downloads need network access. Use
  `--offline` only when the required artifacts are already cached.
- Test observable contracts and important failure paths, not private implementation
  details. Lifecycle changes need race/cancellation coverage; persistence changes need
  real database coverage for constraints, transactions, and migration behavior.
- Run checks appropriate to the change. Documentation-only changes need content/link
  review and whitespace checks; they do not require a server smoke test. After relevant
  checks pass, repeat or broaden them only for a new change or unresolved concern.
- Deploy the shaded `build/libs/OmegaCore-<version>-all.jar`, never the plain JAR.
  Preserve relocations and merged service-provider files. Paper supplies SLF4J; do not
  bundle a competing logging API/backend or test libraries/native binaries.
- Dependency, packaging, scheduler, and lifecycle changes may require disposable Paper
  verification in addition to tests. Build success alone is not runtime evidence.
- Use isolated server directories and databases, temporary ports, and tracked cleanup
  of child processes. Do not use the existing `run/` world or a developer/production
  database without explicit authorization. Review `runServer` settings before use;
  it currently reserves 8 GB and uses the development server directory.
- `build/` smoke scripts and logs are disposable evidence, not required checkout tools.
  Do not make a repeatable workflow depend on an ignored one-off script.
- Keep staged work intact. Do not commit, push, deploy, or modify a live server unless
  the user has authorized that action. Do not change ignore rules merely to expose
  scratch documents.
- Update relevant documentation when behavior or contracts change. Report what changed,
  what was verified, and remaining limits. Separate unit, database, Paper, Folia, and
  production-load evidence; never imply that one proves the others.

## Current handoff — 2026-09-27

Player/session persistence, connection preparation, ordered worker ownership, scheduler
integration, role policy and realm-wide role loading are implemented. Console role changes
now use V005 audit history and atomic authoritative checks; connected snapshots refresh in
bounded batches and expire after five seconds. Physical reconnect runs on the exact player's
entity scheduler. No new resource owners or configuration keys were added.

Java 25 build passed with 250 unit tests and 51 native PostgreSQL tests. The native suite
includes concurrent role writers, audit rollback and propagation between two runtime/server
IDs. Disposable Paper 26.3 build 42 verified console and namespaced changes, OP player denial,
local publication, no-op behavior, expiry during a worker stall, exact session closure and
clean shutdown. Live deployment, production load and Folia were not verified. General vanilla
permission enforcement, OP control and player-facing role commands remain subsequent gates.

The earlier snapshot below records bootstrap evidence only; it is not current feature status.

## Earlier handoff snapshot — 2026-09-26

Completed: strict immutable configuration, asynchronous PostgreSQL bootstrap, Hikari
ownership, Flyway migrations and validation, startup deadlines, readiness admission,
and fail-closed server shutdown. Player and session tables exist, but bootstrap does
not populate them. Player repositories/services, join/quit session behavior, commands,
authorization, and the full gameplay scheduler remain future gates.

Last verified: 53 unit tests and 8 PostgreSQL integration tests passed. Disposable
Paper 26.3 build 42 checks covered shaded startup, restart, missing credentials,
refused connections, delayed readiness/admission, stop during startup, migration
failure, and disable after readiness. A minimal protocol client verified rejection
while starting and login success when ready; this did not prove a full world join
or player/session persistence. Deadline and late-completion behavior was covered by
unit tests; the full 60-second timeout was not separately exercised on Paper. Folia
and production load are unverified.

Treat this as dated evidence, not proof for subsequent edits. When the user authorizes
player/session work, first define persistence, join/quit ordering, disconnect during
load, reconnect, and recovery of sessions left open by a crash. Keep the existing
readiness and shutdown guarantees intact as the application grows.
