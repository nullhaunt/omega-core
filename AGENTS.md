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
  Follow 26.3 development builds without an exact-build startup lock. Build numbers are
  evidence metadata, not compatibility gates. Actual native API linkage or enforcement
  setup failure must still fail closed; other Minecraft versions/brands are outside the target.
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
  The current exception is `authorization/paper/NativeCommandFence`: public Paper nodes
  hide vanilla handlers, so this isolated native dispatcher adapter preserves parsing while
  enforcing fresh authority. Keep further native access confined to an explicit requirement.

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
- UUID is authoritative player identity. Usernames are mutable metadata. Target resolution
  accepts canonical UUIDs or exact current stored names, ignoring ASCII case; ambiguous names
  must fail instead of guessing. Never duplicate name-lookup SQL in command handlers. Session
  uniqueness and recovery need an explicit join/reconnect contract before implementation.
- Role changes require a reason and a durable audit in the same transaction. Recheck stored
  actor/target roles under locks acquired in UUID order; never trust a supplied role snapshot.
  Role commands target persisted players, including offline players, and resolve to UUIDs
  before mutation. Only the actual local console has console authority; RCON, command blocks,
  players and delegated execution do not acquire it.
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
- Write command replies for people, including console replies. Lead with the outcome, use
  natural sentences and restrained formatting, and keep internal status codes, revision
  numbers and audit IDs out of routine confirmations. Retain precise identifiers in audit
  records and diagnostics where operators need them. Group related lines into one message.
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
integration, role policy, realm-wide role loading, console/player role commands and native
permission enforcement are implemented. V005 audit history and stored actor/target checks
protect mutations. Snapshots expire after five seconds. Player attachments default-deny
registered permissions; no wildcard/parent grants or OP establish Omega authority. ADMIN
and OWNER receive the explicit diagnostics/world-settings/maintenance catalog in README.
Native command parsing, execution and suggestions recheck exact fresh authority. Sign command
execution and native OP are disabled. Server/datapack reload is also disabled for console,
because it rebuilds the dispatcher; use full restart. Persisted OP entries are cleared before admission.

ServerLoad installs native enforcement before runtime bootstrap can start and publish READY.
Permission changes reconcile every 250 ms on captured entity handles; stale applied revisions
deny commands, and a five-second application delay requires reconnect. Physical kicks wait
for the owning context. The existing scheduler owns reconciliation/registry refresh tasks;
the permission adapter owns attachments. No new executors or configuration keys were added.
Controlled shutdown/restart, backup/rollback and
general gameplay scheduling are deferred slices.

Java 25 checks passed with 269 unit tests and 51 native PostgreSQL tests. Database evidence
includes concurrent writers, audit rollback and propagation between two runtime/server IDs.
Disposable Paper 26.3 build 42 verified native parsing/tools, namespace and branch masking,
client trees, persisted OP clearing/bypass denial, sign-event cancellation, console reload/OP
denial, audited player role writes, promotion/demotion, expiry and clean shutdown. The final
registry-plan comparison received focused unit/build verification. See the authorization
contract for limits. Physical sign-click, graphical-client, live deployment, production load
and Folia remain unverified.

The exact Paper-build startup lock was removed. Target checks accept Paper 26.3 regardless
of its build number, while actual API linkage/setup failure remains critical. The dev bundle
again follows `26.3.build.+`. The 269-unit suite and disposable Paper builds 42 and 49 passed
after this correction; no developer/production server was modified.

Role-command feedback now separates command access from operation validation. Non-staff
still receive native parser rejection. Authorized staff receive usage and validation messages;
self-changes remain protected and explain the local-console requirement. Stored policy denials
report that no change was made. Revocation after parsing includes native input/cursor context.
This correction passed 274 unit tests, shaded packaging and a fresh isolated Paper build-49
check of client message delivery, primary/namespaced paths, unchanged denial audits/revisions,
role writes, masking, expiry and cleanup. The 51-test database suite above is prior evidence;
the persistence writer was unchanged. Graphical-client rendering remains unverified.

The command organization gate now registers standalone `/role set` and filtered `/help` through
`command/paper/OmegaCommands`; `/omega role set` is retired. `CommandCatalog` supplies names,
descriptions, minimum roles, namespaced variants and help entries. `CommandAccess` uses exact
sender and fresh connected authority for parsing, help and execution; the native fence consumes
the registered labels instead of special-casing `/omega`. `CommandMessages` uses `Ω |` for player
and console feedback. Role changes acknowledge queueing, then reply after worker completion on
the original console/player context. `CommandAudit` writes filterable `STAFF`/`MANAGEMENT`
operational events with actor, server, canonical path, outcome and operation ID; committed role
events link the existing transactional mutation audit. No new pool, timer, schema, or persistent
command-history table was introduced. Staff-command handlers and durable universal invocation
history remain later slices. See README for operator syntax and runtime evidence.

Java 25 `test shadowJar --offline --console=plain` passed with 276 unit tests. A fresh
isolated Paper 26.3 build-49/PostgreSQL run under `build/command-registry-smoke-alias`
verified `/role`, `/help`, both namespace paths, namespaced help arguments, filtered command
trees, protocol-client feedback, queued/final replies, native management parsing, OP denial,
role mutation audits, five-second expiry and clean shutdown. Operational events were checked
for canonical paths, category, outcome and mutation-audit correlation without raw reasons.
The prior 51-test database suite was not rerun because this gate changed no writer or schema.
Graphical-client rendering, live deployment, Folia and production load remain unverified.

The V006 command-audit gate adds one append-only `command_audit` table for accepted Omega
role requests, with shared `STAFF`/`MANAGEMENT` categories. A `QUEUED` event commits on the
existing owned worker before mutation; the final event commits with any role revision and
V005 mutation audit. Failure to persist acceptance prevents mutation, and failure to persist
the final event rolls mutation back. A queued-only event means the final outcome is unknown.
Operational logs still cover pre-acceptance validation denials and native command invocations;
native logs prove invocation only. The table records target UUID and validated requested role;
neither raw arguments nor reasons enter it.
There is no new pool, executor, timer, configuration key, purge job or automatic retention.
At that gate, username/UUID resolution remained a separate task.

Java 25 `test integrationTest build --offline --console=plain` passed with 276 unit and
54 native PostgreSQL tests. A fresh disposable Paper 26.3 build-49/PostgreSQL fixture in
`build/command-audit-smoke-final` verified paired events, all six mutation links, player feedback,
command masking, expiry and clean shutdown. Live deployment, graphical-client rendering,
Folia and production load remain unverified.

The command-audit scope now includes significant native staff/management commands. Reviewed
native handlers enqueue an argument-free `INVOKED` event before execution; other local-console
and RCON native submissions enter through Paper's server-command event. The existing ordered
worker writes them to V006. A full/unavailable queue denies execution, except that `stop`
remains available with an operational log when persistence is down. Normal shutdown drains
accepted pending events within its cleanup deadline. A crash between native effect and SQL
commit can leave a gap; `INVOKED` never proves that the effect succeeded. Role mutations retain
their paired queued/final events and atomic V005 link. Routine player and command-block commands
are outside this table; future significant handlers must use the shared audit entry point.
Java 25 checks passed with 280 unit and 55 native PostgreSQL tests. A fresh disposable Paper
26.3 build-49/PostgreSQL run under `build/significant-command-audit-smoke-2` verified native
player rows, a console `stop` row after clean shutdown, role links, masking and expiry. The
emergency unaudited `stop` fallback was source-checked but not exercised on Paper. Live
deployment, graphical-client rendering, Folia and production load remain unverified.

The reusable player-target gate adds `PlayerTarget` parsing, `PlayerTargetResolver` and
`PlayerTargetResolution` in the player domain. `/role set` uses this service to resolve a
canonical UUID or current stored username to a persisted UUID before calling the existing
role writer. Name matching is exact and case-insensitive; unknown, old or ambiguous names
do not mutate roles. V007 adds a nonunique lookup index. Resolution shares the ordered worker,
bounded mailbox, owned connection and 15-second deadline; stop/failure rejects pending work.
The immediate message acknowledges lookup queueing; V006 `QUEUED`/final rows begin only when
a resolved role mutation is accepted. No external identity service, executor or config key was
added. Java 25 `test integrationTest build --offline --console=plain` passed with 289 unit
and 56 native PostgreSQL tests; a fresh isolated Paper 26.3 build-49/PostgreSQL run under
`build/player-target-smoke` verified mixed-case name commands, unknown-name feedback,
player replies, role/audit writes, expiry and orderly shutdown. The fixture processes stopped;
live deployment, graphical-client rendering, Folia and production load remain unverified.
See README and `docs/AUTHORIZATION.md` for operator semantics.

The read-only management diagnostics gate registers standalone `/build` and `/status`,
plus their `omegacore:` labels. Fresh ADMIN/OWNER players and the actual local console
can use them; lower roles, RCON and delegated execution cannot. `/build` shows the
version, abbreviated Git revision and readable UTC build time embedded in the shaded
JAR. Checkout state is not part of the production command. `/status` reads bounded
in-memory runtime/player-service observations, including the last completed database
connection and server ID lock check and oldest connected authorization age. It performs
no SQL or scheduler wait. The database line describes a past successful check, not live
availability.
These read-only invocations use the shared operational `MANAGEMENT` log and remain
available if the durable audit queue is unavailable. No new schema, worker, timer or
configuration key was added. Java 25 checks passed with 291 unit and 56 native
PostgreSQL tests. A fresh isolated Paper 26.3 build-49/PostgreSQL fixture under
`build/management-diagnostics-smoke` verified console and player paths, namespace
variants, PLAYER masking, ADMIN promotion, client feedback, revocation and clean
shutdown. The presentation correction grouped fields into one colored player message
or plain console message, shortened the revision, removed checkout state and rendered
elapsed times for operators. Java 25 unit tests and shaded packaging passed again;
the refreshed isolated Paper fixture verified delivery and formatting markers. Its
processes stopped.
The database wording was then clarified to show that the retained connection last
completed a query while holding its server ID lock. Unit and shaded checks cover this
text change; the earlier Paper run remains transport evidence. Graphical-client
rendering, live deployment, Folia and production load remain unverified.

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
