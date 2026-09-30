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

## Work together through gates

- Work in small, explicitly agreed gates or chunks, one at a time. A feature plan is
  not authorization to implement every layer at once. Do not bundle persistence,
  target resolution, commands, chat, and dialogs into one implementation pass.
- Before each gate, state its scope, intended behavior, acceptance criteria, and what
  remains for later gates. Settle material contract decisions before implementation.
- Finish the gate's appropriate automated checks, then provide a concrete, reviewable
  result and concise testing instructions. For player-facing changes, stop at that
  checkpoint so the user can test in-game and offer feedback before proceeding.
- The user supplies actual in-game acceptance evidence; the agent cannot substitute
  unit tests, protocol fixtures, or build success for the user's Java/Bedrock experience.
  State which checks the agent performed and which still need the user's feedback.
- Incorporate that feedback within the current gate before agreeing on the next scope.
  Do not automatically advance to the next gate or ask the user to repeat checks they
  have already confirmed. Documentation-only gates need review, not an in-game test.

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
  accepts canonical UUIDs or exact current stored usernames/nicknames, ignoring ASCII case;
  matches to different UUIDs are ambiguous and must fail instead of guessing. Historical
  account-name reservations are not aliases. Resolve once and retain that UUID for mutations.
  Never duplicate name-lookup SQL in command handlers. Session
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
- Every registered Omega command must declare nonblank syntax and a description in the
  shared catalog alongside its access policy. Bind catalog definitions to Paper nodes
  through the shared registration path, and derive `/help` from that metadata without
  command-specific branches. Do not repeat tab-completion choices in help or usage;
  keep validation replies specific to the failed input or policy.
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
