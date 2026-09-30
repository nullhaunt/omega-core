package com.omegarealm.core.player;

import com.omegarealm.core.authorization.AuthorizationRefreshLimits;
import com.omegarealm.core.authorization.AuthorizationSnapshot;
import com.omegarealm.core.authorization.RoleChangeRequest;
import com.omegarealm.core.authorization.RoleChangeResult;
import com.omegarealm.core.command.CommandAuditEvent;
import com.omegarealm.core.command.CommandAuditEvent.Outcome;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static com.omegarealm.core.player.PlayerOperationException.Kind;

/**
 * Owns connection attempts, ordered persistence, and repository lifetime. Runtime owns
 * the worker and clock. One mailbox pump executes all SQL on the repository's I/O thread.
 * Neither futures nor failure callbacks are completed while holding the state monitor.
 */
public final class PlayerService implements PlayerTargetResolver {

    private final Object ownership = new Object();
    private final PlayerSessionRepository repository;
    private final PlayerProfileRepository profiles;
    private final Executor worker;
    private final ScheduledExecutorService deadlines;
    private final Consumer<String> criticalFailure;
    private final Logger logger;
    private final ConnectionTimings connectionTimings;
    private final Clock time;
    private final PlayerServiceLimits limits;
    private final AuthorizationRefreshLimits authorizationLimits;
    private final Thread ioThread;
    private final Map<UUID, Entry> attempts = new HashMap<>();
    private final Map<UUID, Entry> currentPlayers = new HashMap<>();
    private final ArrayDeque<Work> mailbox = new ArrayDeque<>();
    private final ArrayDeque<CommandAuditEvent> shutdownAudits = new ArrayDeque<>();
    private final Set<RoleMutation> roleChanges = new HashSet<>();
    private final Set<TargetLookup<?>> targetLookups = new HashSet<>();
    private final Set<ProfileTask<?>> profileOperations = new HashSet<>();
    private State state = State.STARTING;
    private boolean pumpScheduled;
    private Work inFlight;
    private boolean ownershipCheckPending;
    private long ownershipCheckSequence;
    private long ownershipVerifiedAtNanos;
    private boolean ownershipVerified;
    private ScheduledFuture<?> ownershipPoll;
    private ScheduledFuture<?> ownershipDeadline;
    private ScheduledFuture<?> authorizationPoll;
    private boolean authorizationRefreshPending;
    private Instant stoppedAt;
    private long cleanupDeadline;
    private boolean cleanlyClosed = true;

    /**
     * Construct and initialize on the same worker that acquired the repository.
     */
    public PlayerService(PlayerSessionRepository repository, Executor worker,
                         ScheduledExecutorService deadlines, Consumer<String> criticalFailure,
                         Logger logger, Clock time, PlayerServiceLimits limits) {
        this(repository, worker, deadlines, criticalFailure, logger, time, limits, AuthorizationRefreshLimits.DEFAULT);
    }

    public PlayerService(PlayerSessionRepository repository, Executor worker,
                         ScheduledExecutorService deadlines, Consumer<String> criticalFailure,
                         Logger logger, Clock time, PlayerServiceLimits limits, boolean debug) {
        this(repository, worker, deadlines, criticalFailure, logger, time, limits,
                AuthorizationRefreshLimits.DEFAULT, debug);
    }

    public PlayerService(PlayerSessionRepository repository, Executor worker,
                         ScheduledExecutorService deadlines, Consumer<String> criticalFailure,
                         Logger logger, Clock time, PlayerServiceLimits limits,
                         AuthorizationRefreshLimits authorizationLimits) {
        this(repository, worker, deadlines, criticalFailure, logger, time, limits,
                authorizationLimits, false);
    }

    public PlayerService(PlayerSessionRepository repository, Executor worker,
                         ScheduledExecutorService deadlines, Consumer<String> criticalFailure,
                         Logger logger, Clock time, PlayerServiceLimits limits,
                         AuthorizationRefreshLimits authorizationLimits, boolean debug) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.profiles = Objects.requireNonNull(repository.profiles(), "profiles");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
        this.criticalFailure = Objects.requireNonNull(criticalFailure, "criticalFailure");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.connectionTimings = new ConnectionTimings(logger, debug);
        this.time = Objects.requireNonNull(time, "time");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.authorizationLimits = Objects.requireNonNull(authorizationLimits, "authorizationLimits");
        ioThread = Thread.currentThread();
    }

    private static void cancel(ScheduledFuture<?> timer) {
        if (timer != null) {
            timer.cancel(false);
        }
    }

    private static <T> CompletionStage<T> rejected(Kind kind) {
        return CompletableFuture.<T>failedFuture(new PlayerOperationException(kind)).minimalCompletionStage();
    }

    public void startOnWorker() throws PersistenceException {
        requireIoThread();
        int recovered = repository.recoverInterrupted(time.instant());
        synchronized (ownership) {
            if (state != State.STARTING) {
                return;
            }
            state = State.RUNNING;
            ownershipVerifiedAtNanos = System.nanoTime();
            ownershipVerified = true;
            ownershipPoll = deadlines.scheduleWithFixedDelay(this::requestOwnershipCheck,
                    limits.ownershipInterval().toNanos(), limits.ownershipInterval().toNanos(),
                    TimeUnit.NANOSECONDS);
            authorizationPoll = deadlines.scheduleWithFixedDelay(this::requestAuthorizationRefresh,
                    authorizationLimits.interval().toNanos(), authorizationLimits.interval().toNanos(),
                    TimeUnit.NANOSECONDS);
        }
        logger.info("Player service initialized: recovered " + recovered + " interrupted session(s).");
    }

    public boolean isRunning() {
        synchronized (ownership) {
            return state == State.RUNNING;
        }
    }

    /**
     * Reads only bounded in-memory state. A committed session is counted until its ordered
     * close completes, including while a departure is queued.
     */
    public PlayerServiceDiagnostics diagnostics() {
        synchronized (ownership) {
            long now = System.nanoTime();
            int connected = 0;
            int openSessions = 0;
            int expired = 0;
            long oldestAuthorizationNanos = -1;

            for (Entry entry : attempts.values()) {
                if (entry.committed) {
                    openSessions++;
                }
                if (!authorityPhase(entry)) {
                    continue;
                }

                connected++;
                long age = Math.max(0L, now - entry.authorizationVerifiedAt);
                oldestAuthorizationNanos = Math.max(oldestAuthorizationNanos, age);
                if (entry.authorization == null || entry.authorizationExpired
                        || age >= authorizationLimits.freshness().toNanos()) {
                    expired++;
                }
            }

            Optional<Duration> ownershipAge = ownershipVerified
                    ? Optional.of(Duration.ofNanos(Math.max(0L, now - ownershipVerifiedAtNanos)))
                    : Optional.empty();
            Optional<Duration> authorityAge = oldestAuthorizationNanos >= 0
                    ? Optional.of(Duration.ofNanos(oldestAuthorizationNanos))
                    : Optional.empty();
            int pending = mailbox.size() + (inFlight == null ? 0 : 1);

            return new PlayerServiceDiagnostics(state == State.RUNNING, connected, openSessions,
                    pending, ownershipAge, ownershipCheckPending, authorityAge, expired);
        }
    }

    /**
     * Readiness is an in-memory token check, suitable for a later login validation hook.
     */
    public boolean isPrepared(UUID token) {
        synchronized (ownership) {
            Entry entry = attempts.get(token);
            return state == State.RUNNING && entry != null && entry.phase == Phase.PREPARED && fresh(entry);
        }
    }

    /**
     * Reads the snapshot owned by this exact prepared/joining/active connection without I/O.
     * Callers must recheck this boundary; a previously returned value is not a live credential.
     */
    public Optional<AuthorizationSnapshot> authorization(UUID token) {
        Objects.requireNonNull(token, "token");
        synchronized (ownership) {
            Entry entry = attempts.get(token);
            if (state != State.RUNNING || entry == null || !fresh(entry)
                    || (entry.phase != Phase.PREPARED && entry.phase != Phase.JOINING && entry.phase != Phase.ACTIVE)) {
                return Optional.empty();
            }
            return Optional.ofNullable(entry.authorization);
        }
    }

    /**
     * Sticky expiration: a later read cannot restore authority to this connection.
     */
    public boolean requiresAuthorizationReconnect(UUID token) {
        synchronized (ownership) {
            Entry entry = attempts.get(token);
            return state == State.RUNNING && entry != null && authorityPhase(entry) && !fresh(entry);
        }
    }

    /**
     * Reads profile data for an exact active connection; no display cache is introduced here.
     */
    public CompletionStage<Optional<PlayerNickname>> loadNickname(UUID token) {
        return submitProfile(token, PlayerProfileRepository::loadNickname);
    }

    /**
     * Self-service only: the UUID comes from the exact connection, never from client input.
     */
    public CompletionStage<NicknameChangeResult> changeNickname(UUID token, long expectedRevision, String nickname) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("An edit revision is required.");
        }
        return submitProfile(token, (profileRepository, uuid) -> profileRepository.changeNickname(
                new NicknameChange(uuid, expectedRevision, nickname)));
    }

    public CompletionStage<List<NicknameNotice>> pendingNicknameNotices(UUID token) {
        return submitProfile(token, PlayerProfileRepository::pendingNicknameNotices);
    }

    /**
     * Delivery belongs to a later Paper gate; call only after its private delivery succeeds.
     */
    public CompletionStage<Boolean> acknowledgeNicknameNotice(UUID token, UUID noticeId) {
        Objects.requireNonNull(noticeId, "noticeId");
        return submitProfile(token, (profileRepository, uuid) ->
                profileRepository.acknowledgeNicknameNotice(uuid, noticeId));
    }

    private <T> CompletionStage<T> submitProfile(UUID token, ProfileOperation<T> action) {
        Objects.requireNonNull(token, "token");
        ProfileTask<T> task;
        try {
            synchronized (ownership) {
                if (state != State.RUNNING) {
                    return rejected(Kind.UNAVAILABLE);
                }
                Entry entry = attempts.get(token);
                if (!profileConnection(entry)) {
                    return rejected(Kind.CANCELLED);
                }
                if (mailboxFull()) {
                    return rejected(Kind.BUSY);
                }
                task = new ProfileTask<>(entry, action);
                task.deadline = deadlines.schedule(() -> failService(
                                "Player profile operation exceeded its deadline; inspect audit history before retrying.",
                                () -> profileOperations.contains(task)),
                        limits.mutationTimeout().toNanos(), TimeUnit.NANOSECONDS);
                profileOperations.add(task);
                mailbox.addLast(new Work(null, Operation.PROFILE, null, null, null, task));
            }
        } catch (RejectedExecutionException unavailableClock) {
            failService("Player deadline clock is unavailable.");
            return rejected(Kind.UNAVAILABLE);
        }
        kick();
        return task.result.minimalCompletionStage();
    }

    // Called under the state monitor. Old tokens and terminal authority cannot edit a profile.
    private boolean profileConnection(Entry entry) {
        return entry != null && attempts.get(entry.attempt.token()) == entry
                && entry.phase == Phase.ACTIVE && entry.committed && fresh(entry);
    }

    public CompletionStage<RoleChangeResult> changeRole(RoleChangeRequest request) {
        Objects.requireNonNull(request, "request");
        RoleMutation mutation = new RoleMutation(request);
        try {
            synchronized (ownership) {
                if (state != State.RUNNING) {
                    return rejected(Kind.UNAVAILABLE);
                }
                if (mailboxFull()) {
                    return rejected(Kind.BUSY);
                }
                mutation.deadline = deadlines.schedule(() -> failService(
                                "Role change exceeded its deadline; inspect audit history before retrying.",
                                () -> roleChanges.contains(mutation)),
                        limits.mutationTimeout().toNanos(), TimeUnit.NANOSECONDS);
                roleChanges.add(mutation);
                mailbox.addLast(new Work(null, Operation.ROLE_CHANGE, mutation));
            }
        } catch (RejectedExecutionException unavailableClock) {
            failService("Player deadline clock is unavailable.");
            return rejected(Kind.UNAVAILABLE);
        }
        kick();
        return mutation.result.minimalCompletionStage();
    }

    @Override
    public CompletionStage<PlayerTargetResolution> resolveTarget(PlayerTarget target) {
        Objects.requireNonNull(target, "target");
        return submitTargetRead(repository -> repository.resolveTarget(target));
    }

    public CompletionStage<List<String>> suggestTargets(String prefix) {
        if (!PlayerTarget.validNamePrefix(prefix)) {
            return CompletableFuture.completedStage(List.of());
        }
        return submitTargetRead(repository -> repository.suggestTargets(prefix));
    }

    private <T> CompletionStage<T> submitTargetRead(TargetRead<T> read) {
        TargetLookup<T> lookup = new TargetLookup<>(read);
        try {
            synchronized (ownership) {
                if (state != State.RUNNING) {
                    return rejected(Kind.UNAVAILABLE);
                }
                if (mailboxFull()) {
                    return rejected(Kind.BUSY);
                }
                lookup.deadline = deadlines.schedule(() -> failService(
                                "Player target lookup exceeded its deadline.",
                                () -> targetLookups.contains(lookup)),
                        limits.mutationTimeout().toNanos(), TimeUnit.NANOSECONDS);
                targetLookups.add(lookup);
                mailbox.addLast(new Work(null, Operation.TARGET_LOOKUP, null, null, lookup));
            }
        } catch (RejectedExecutionException unavailableClock) {
            failService("Player deadline clock is unavailable.");
            return rejected(Kind.UNAVAILABLE);
        }
        kick();
        return lookup.result.minimalCompletionStage();
    }

    /**
     * Accepts an invocation without blocking a Paper command thread on SQL. Rejection means
     * the command handler must not run. Accepted writes stay ordered with other persistence.
     */
    public boolean queueCommandAudit(CommandAuditEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.outcome() != Outcome.INVOKED) {
            throw new IllegalArgumentException("Only command invocations use the shared queue.");
        }
        synchronized (ownership) {
            if (state != State.RUNNING || mailboxFull()) {
                return false;
            }
            mailbox.addLast(new Work(null, Operation.COMMAND_AUDIT, null, event));
        }
        kick();
        synchronized (ownership) {
            return state == State.RUNNING;
        }
    }

    private boolean authorityPhase(Entry entry) {
        return entry.phase == Phase.PREPARED || entry.phase == Phase.JOINING || entry.phase == Phase.ACTIVE;
    }

    private boolean fresh(Entry entry) {
        return entry.authorization != null && !entry.authorizationExpired
                && System.nanoTime() - entry.authorizationVerifiedAt < authorizationLimits.freshness().toNanos();
    }

    public CompletionStage<PreparedPlayer> prepare(PlayerAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        Entry entry;
        try {
            synchronized (ownership) {
                if (state != State.RUNNING) {
                    return rejected(Kind.UNAVAILABLE);
                }
                Entry existing = attempts.get(attempt.token());
                if (existing != null) {
                    return existing.attempt.equals(attempt)
                            ? existing.preparation.minimalCompletionStage() : rejected(Kind.DUPLICATE);
                }
                Entry previous = currentPlayers.get(attempt.identity().uuid());
                if (previous != null && previous.phase != Phase.CLOSING) {
                    return rejected(Kind.DUPLICATE);
                }
                if (attempts.size() >= limits.trackedAttempts() || mailboxFull()) {
                    return rejected(Kind.BUSY);
                }

                entry = new Entry(attempt);
                entry.deadline = deadlines.schedule(() -> expire(entry, Phase.LOADING),
                        limits.loadTimeout().toNanos(), TimeUnit.NANOSECONDS);
                attempts.put(attempt.token(), entry);
                currentPlayers.put(attempt.identity().uuid(), entry);
                if (connectionTimings.enabled()) {
                    entry.queuedAtNanos = System.nanoTime();
                }
                mailbox.addLast(new Work(entry, Operation.LOAD));
            }
        } catch (RejectedExecutionException unavailableClock) {
            failService("Player deadline clock is unavailable.");
            return rejected(Kind.UNAVAILABLE);
        }
        kick();
        return entry.preparation.minimalCompletionStage();
    }

    /**
     * Call only after actual world join; preparation itself never writes player history.
     */
    public CompletionStage<PlayerProfile> activate(UUID token, Instant joinedAt) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(joinedAt, "joinedAt");
        Instant joined = joinedAt.truncatedTo(ChronoUnit.MICROS);
        Entry entry;
        boolean saturated;
        try {
            synchronized (ownership) {
                if (state != State.RUNNING) {
                    return rejected(Kind.UNAVAILABLE);
                }
                entry = attempts.get(token);
                if (entry == null) {
                    return rejected(Kind.NOT_PREPARED);
                }
                if ((entry.phase == Phase.JOINING || entry.phase == Phase.ACTIVE)
                        && joined.equals(entry.joinedAt)) {
                    return entry.activation.minimalCompletionStage();
                }
                if (entry.phase != Phase.PREPARED) {
                    return rejected(Kind.NOT_PREPARED);
                }

                cancel(entry.deadline);
                entry.phase = Phase.JOINING;
                entry.joinedAt = joined;
                entry.deadline = mutationDeadline(entry, Phase.JOINING);
                saturated = mailboxFull();
                if (!saturated) {
                    mailbox.addLast(new Work(entry, Operation.JOIN));
                }
            }
        } catch (RejectedExecutionException unavailableClock) {
            failService("Player deadline clock is unavailable.");
            return rejected(Kind.UNAVAILABLE);
        }
        if (saturated) {
            // Keep the observed join for shutdown draining; silently dropping it is unsafe.
            failService("Player persistence mutation queue is full.");
        } else {
            kick();
        }
        return entry.activation.minimalCompletionStage();
    }

    /**
     * Invalidates the exact attempt immediately; persistence is ordered after its join.
     */
    public CompletionStage<Void> disconnect(UUID token, Instant leftAt) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(leftAt, "leftAt");
        Instant left = leftAt.truncatedTo(ChronoUnit.MICROS);
        Entry entry;
        boolean cancelledLoad = false;
        boolean saturated = false;
        try {
            synchronized (ownership) {
                entry = attempts.get(token);
                if (entry == null) {
                    return CompletableFuture.<Void>completedFuture(null).minimalCompletionStage();
                }
                if (state != State.RUNNING) {
                    return rejected(Kind.UNAVAILABLE);
                }
                if (entry.phase == Phase.CLOSING) {
                    return entry.departure.minimalCompletionStage();
                }
                if (entry.phase == Phase.LOADING || entry.phase == Phase.PREPARED) {
                    invalidate(entry);
                    cancelledLoad = true;
                } else {
                    if (left.isBefore(entry.joinedAt)) {
                        throw new IllegalArgumentException("Player disconnect precedes join.");
                    }
                    cancel(entry.deadline);
                    entry.phase = Phase.CLOSING;
                    entry.authorization = null;
                    entry.leftAt = left;
                    entry.deadline = mutationDeadline(entry, Phase.CLOSING);
                    saturated = mailboxFull();
                    if (!saturated) {
                        mailbox.addLast(new Work(entry, Operation.QUIT));
                    }
                }
            }
        } catch (RejectedExecutionException unavailableClock) {
            failService("Player deadline clock is unavailable.");
            return rejected(Kind.UNAVAILABLE);
        }
        entry.preparation.completeExceptionally(new PlayerOperationException(Kind.CANCELLED));
        entry.activation.completeExceptionally(new PlayerOperationException(Kind.CANCELLED));
        if (cancelledLoad) {
            entry.departure.complete(null);
        } else if (saturated) {
            failService("Player persistence mutation queue is full.");
        } else {
            kick();
        }
        return entry.departure.minimalCompletionStage();
    }

    /**
     * Nonblocking shutdown signal; only the runtime's worker may perform the drain.
     */
    public void requestStop(Instant at) {
        Objects.requireNonNull(at, "at");
        List<Entry> pending;
        synchronized (ownership) {
            if (state == State.STOPPING || state == State.CLOSED) {
                return;
            }
            state = State.STOPPING;
            stoppedAt = at.truncatedTo(ChronoUnit.MICROS);
            cleanupDeadline = System.nanoTime() + limits.cleanupTimeout().toNanos();
            stopTimers();
            for (Work work : mailbox) {
                if (work.commandAudit() != null) {
                    shutdownAudits.addLast(work.commandAudit());
                }
            }
            mailbox.clear();
            pending = new ArrayList<>(attempts.values());
        }
        rejectPending(pending);
        rejectRoleChanges();
        rejectTargetLookups();
        rejectProfileOperations();
    }

    /**
     * Shutdown-only resource cleanup. Runtime closes the pool after this returns.
     */
    public boolean closeOnWorker() {
        requireIoThread();
        requestStop(time.instant());
        List<Entry> draining;
        List<CommandAuditEvent> audits;
        synchronized (ownership) {
            if (state == State.CLOSED) {
                return cleanlyClosed;
            }
            if (inFlight != null) {
                throw new IllegalStateException("Player cleanup cannot run inside a mailbox operation.");
            }
            draining = new ArrayList<>(attempts.values());
            audits = new ArrayList<>(shutdownAudits);
            shutdownAudits.clear();
        }

        boolean interrupted = Thread.interrupted();
        boolean clean = true;
        try {
            for (Entry entry : draining) {
                if (entry.joinedAt == null) {
                    continue;
                }
                if (System.nanoTime() - cleanupDeadline >= 0) {
                    logger.severe("Player session cleanup exceeded its deadline; unfinished sessions require recovery.");
                    clean = false;
                    break;
                }
                try {
                    if (!entry.committed) {
                        repository.join(sessionStart(entry));
                    }
                    Instant end = entry.leftAt == null ? stoppedAt : entry.leftAt;
                    SessionEnd reason = entry.leftAt == null ? SessionEnd.SHUTDOWN : SessionEnd.QUIT;
                    if (!repository.endSession(entry.attempt.token(), end, reason)) {
                        throw new IllegalStateException("Observed player session disappeared during cleanup.");
                    }
                } catch (PersistenceException | RuntimeException failure) {
                    logger.severe("Player session cleanup failed; unfinished sessions require recovery.");
                    clean = false;
                    break;
                }
            }
            for (CommandAuditEvent audit : audits) {
                if (System.nanoTime() - cleanupDeadline >= 0) {
                    logger.severe("Command audit cleanup exceeded its deadline; inspect operational logs.");
                    clean = false;
                    break;
                }
                try {
                    repository.appendCommandAudit(audit);
                } catch (PersistenceException | RuntimeException failure) {
                    logger.severe("Command audit cleanup failed; inspect operational logs.");
                    clean = false;
                    break;
                }
            }
        } finally {
            try {
                repository.close();
            } catch (PersistenceException | RuntimeException failure) {
                logger.severe("Player repository cleanup failed; verify server ownership before restarting.");
                clean = false;
            }
            synchronized (ownership) {
                attempts.clear();
                currentPlayers.clear();
                mailbox.clear();
                state = State.CLOSED;
                cleanlyClosed = clean;
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return clean;
    }

    private boolean mailboxFull() {
        return mailbox.size() + (inFlight == null ? 0 : 1) >= limits.pendingOperations();
    }

    private void kick() {
        synchronized (ownership) {
            if (state != State.RUNNING || pumpScheduled
                    || (mailbox.isEmpty() && !ownershipCheckPending && !authorizationRefreshPending)) {
                return;
            }
            pumpScheduled = true;
        }
        try {
            worker.execute(this::drainMailbox);
        } catch (RejectedExecutionException rejectedWorker) {
            failService("Player I/O worker is unavailable.");
        }
    }

    private void drainMailbox() {
        requireIoThread();
        while (true) {
            Work work;
            boolean checkOwner;
            boolean refreshAuthority;
            Runnable completion = null;
            synchronized (ownership) {
                if (state != State.RUNNING) {
                    pumpScheduled = false;
                    return;
                }
                checkOwner = ownershipCheckPending;
                refreshAuthority = !checkOwner && authorizationRefreshPending;
                if (refreshAuthority) {
                    authorizationRefreshPending = false;
                }
                work = checkOwner || refreshAuthority ? null : mailbox.pollFirst();
                if (!checkOwner && !refreshAuthority && work == null) {
                    pumpScheduled = false;
                    return;
                }
                inFlight = work;
            }

            try {
                if (checkOwner) {
                    repository.checkOwnership();
                    synchronized (ownership) {
                        if (state == State.RUNNING) {
                            ownershipVerifiedAtNanos = System.nanoTime();
                        }
                        ownershipCheckPending = false;
                        cancel(ownershipDeadline);
                        ownershipDeadline = null;
                    }
                } else if (refreshAuthority) {
                    refreshAuthorizations();
                } else {
                    switch (work.operation()) {
                        case LOAD -> load(work.entry());
                        case JOIN -> join(work.entry());
                        case QUIT -> quit(work.entry());
                        case ROLE_CHANGE -> changeRoleOnWorker(work.mutation());
                        case COMMAND_AUDIT -> repository.appendCommandAudit(work.commandAudit());
                        case TARGET_LOOKUP -> completion = resolveTargetOnWorker(work.targetLookup());
                        case PROFILE -> completion = profileOnWorker(work.profileTask());
                    }
                }
            } catch (PersistenceException failure) {
                failService(failure.getMessage());
            } catch (Exception | LinkageError failure) {
                failService("Unexpected player persistence failure.");
            } finally {
                synchronized (ownership) {
                    inFlight = null;
                }
            }
            if (completion != null) {
                completion.run();
            }
        }
    }

    private void load(Entry entry) throws PersistenceException {
        long queueNanos = connectionTimings.enabled()
                ? System.nanoTime() - entry.queuedAtNanos : 0L;
        long databaseNanos = 0L;
        ConnectionTimings.Outcome outcome = ConnectionTimings.Outcome.FAILED;

        try {
            synchronized (ownership) {
                if (entry.phase != Phase.LOADING) {
                    outcome = ConnectionTimings.Outcome.CANCELLED;
                    return;
                }
            }

            long verifiedAt = System.nanoTime();
            LoadedPlayer loaded;
            try {
                loaded = repository.loadPlayer(entry.attempt.identity().uuid());
            } finally {
                if (connectionTimings.enabled()) {
                    databaseNanos = System.nanoTime() - verifiedAt;
                }
            }

            PreparedPlayer prepared = new PreparedPlayer(entry.attempt, loaded.storedProfile(), loaded.authorization());
            boolean expired;
            synchronized (ownership) {
                if (state != State.RUNNING || entry.phase != Phase.LOADING) {
                    outcome = ConnectionTimings.Outcome.DISCARDED;
                    return;
                }
                cancel(entry.deadline);
                expired = System.nanoTime() - verifiedAt >= authorizationLimits.freshness().toNanos();
                if (expired) {
                    invalidate(entry);
                } else {
                    entry.authorization = prepared.authorization();
                    entry.persisted = loaded.storedProfile().isPresent();
                    entry.phase = Phase.PREPARED;
                    renewAuthorization(entry, verifiedAt);
                    entry.deadline = deadlines.schedule(() -> expire(entry, Phase.PREPARED),
                            limits.preparedTimeout().toNanos(), TimeUnit.NANOSECONDS);
                }
            }
            if (expired) {
                outcome = ConnectionTimings.Outcome.EXPIRED;
                entry.preparation.completeExceptionally(new PlayerOperationException(Kind.TIMED_OUT));
            } else {
                outcome = ConnectionTimings.Outcome.PREPARED;
                entry.preparation.complete(prepared);
            }
        } finally {
            connectionTimings.preparation(queueNanos, databaseNanos, outcome);
        }
    }

    private void join(Entry entry) throws PersistenceException {
        PlayerProfile profile = repository.join(sessionStart(entry));
        boolean publish;
        synchronized (ownership) {
            entry.committed = true;
            entry.persisted = true;
            publish = state == State.RUNNING && entry.phase == Phase.JOINING;
            if (publish) {
                entry.phase = Phase.ACTIVE;
                cancel(entry.deadline);
                entry.deadline = null;
            }
        }
        if (publish) {
            entry.activation.complete(profile);
        }
    }

    private void quit(Entry entry) throws PersistenceException {
        if (!entry.committed) {
            throw new IllegalStateException("Player departure must follow committed join.");
        }
        if (!repository.endSession(entry.attempt.token(), entry.leftAt, SessionEnd.QUIT)) {
            throw new IllegalStateException("Committed player session disappeared.");
        }
        synchronized (ownership) {
            remove(entry);
        }
        entry.departure.complete(null);
    }

    private SessionStart sessionStart(Entry entry) {
        return new SessionStart(entry.attempt.token(), entry.attempt.identity().uuid(),
                entry.attempt.identity().username(), entry.joinedAt);
    }

    private ScheduledFuture<?> mutationDeadline(Entry entry, Phase phase) {
        return deadlines.schedule(() -> failService("Player persistence mutation exceeded its deadline.",
                        () -> attempts.get(entry.attempt.token()) == entry && entry.phase == phase),
                limits.mutationTimeout().toNanos(), TimeUnit.NANOSECONDS);
    }

    private void expire(Entry entry, Phase phase) {
        synchronized (ownership) {
            if (state != State.RUNNING || entry.phase != phase) {
                return;
            }
            invalidate(entry);
        }
        PlayerOperationException failure = new PlayerOperationException(Kind.TIMED_OUT);
        entry.preparation.completeExceptionally(failure);
        entry.activation.completeExceptionally(failure);
        entry.departure.completeExceptionally(failure);
    }

    private void invalidate(Entry entry) {
        entry.phase = Phase.CANCELLED;
        mailbox.removeIf(work -> work.entry() == entry);
        remove(entry);
    }

    private void remove(Entry entry) {
        entry.authorization = null;
        cancel(entry.authorizationDeadline);
        cancel(entry.deadline);
        attempts.remove(entry.attempt.token(), entry);
        currentPlayers.remove(entry.attempt.identity().uuid(), entry);
    }

    private void requestAuthorizationRefresh() {
        synchronized (ownership) {
            if (state != State.RUNNING) {
                return;
            }
            authorizationRefreshPending = true;
        }
        kick();
    }

    private void refreshAuthorizations() throws PersistenceException {
        List<Entry> connected;
        synchronized (ownership) {
            connected = attempts.values().stream().filter(entry -> authorityPhase(entry) && fresh(entry)).toList();
        }
        for (int offset = 0; offset < connected.size(); offset += authorizationLimits.batchSize()) {
            List<Entry> batch;
            synchronized (ownership) {
                if (state != State.RUNNING) {
                    return;
                }
                // Earlier batches can be delayed while these attempts quit or expire.
                // Claim only the still-current entries before issuing another read.
                batch = connected.subList(offset, Math.min(connected.size(), offset + authorizationLimits.batchSize()))
                        .stream().filter(entry -> attempts.get(entry.attempt.token()) == entry
                                && authorityPhase(entry) && fresh(entry)).toList();
            }
            if (batch.isEmpty()) {
                continue;
            }
            long verifiedAt = System.nanoTime();
            Map<UUID, AuthorizationSnapshot> loaded = repository.loadAuthorizations(
                    batch.stream().map(entry -> entry.attempt.identity().uuid()).toList());
            synchronized (ownership) {
                if (state != State.RUNNING) {
                    return;
                }
                for (Entry entry : batch) {
                    if (attempts.get(entry.attempt.token()) != entry || !authorityPhase(entry) || !fresh(entry)) {
                        continue;
                    }
                    AuthorizationSnapshot snapshot = loaded.get(entry.attempt.identity().uuid());
                    if (snapshot == null) {
                        if (entry.persisted) {
                            throw new IllegalStateException("Existing player authority disappeared during refresh.");
                        }
                        snapshot = AuthorizationSnapshot.initial(entry.attempt.identity().uuid());
                    }
                    publishAuthorization(entry, snapshot, verifiedAt);
                }
            }
        }
    }

    /**
     * Caller holds ownership; the watermark predates the database observation.
     */
    private void publishAuthorization(Entry entry, AuthorizationSnapshot snapshot, long verifiedAt) {
        AuthorizationSnapshot previous = entry.authorization;
        if (!snapshot.playerUuid().equals(entry.attempt.identity().uuid())
                || snapshot.revision() < previous.revision()
                || (snapshot.revision() == previous.revision() && snapshot.role() != previous.role())) {
            throw new IllegalStateException("Authorization revision or identity conflicts.");
        }
        if (System.nanoTime() - verifiedAt < authorizationLimits.freshness().toNanos()) {
            entry.authorization = snapshot;
            renewAuthorization(entry, verifiedAt);
        }
    }

    private void renewAuthorization(Entry entry, long verifiedAt) {
        cancel(entry.authorizationDeadline);
        entry.authorizationVerifiedAt = verifiedAt;
        long sequence = ++entry.authorizationSequence;
        long remaining = authorizationLimits.freshness().toNanos() - (System.nanoTime() - verifiedAt);
        entry.authorizationDeadline = deadlines.schedule(() -> expireAuthorization(entry, sequence),
                Math.max(0, remaining), TimeUnit.NANOSECONDS);
    }

    private void expireAuthorization(Entry entry, long sequence) {
        synchronized (ownership) {
            if (state != State.RUNNING || attempts.get(entry.attempt.token()) != entry
                    || !authorityPhase(entry) || entry.authorizationSequence != sequence || entry.authorizationExpired) {
                return;
            }
            entry.authorizationExpired = true;
            entry.authorization = null;
        }
        logger.warning("Authorization freshness expired: connection=" + entry.attempt.token() + "; reconnect required.");
    }

    private void changeRoleOnWorker(RoleMutation mutation) throws PersistenceException {
        Entry target;
        synchronized (ownership) {
            target = currentPlayers.get(mutation.request.targetUuid());
        }
        long verifiedAt = System.nanoTime();
        RoleChangeResult result = repository.changeRole(mutation.request);
        boolean publish;
        synchronized (ownership) {
            publish = state == State.RUNNING;
            if (publish && result.authorization() != null && target != null
                    && attempts.get(target.attempt.token()) == target && authorityPhase(target) && fresh(target)) {
                publishAuthorization(target, result.authorization(), verifiedAt);
            }
            roleChanges.remove(mutation);
            cancel(mutation.deadline);
        }
        if (result.status() == RoleChangeResult.Status.CHANGED) {
            logger.info("Role change committed: audit=" + result.auditId() + ", target="
                    + result.authorization().playerUuid() + ", role=" + result.authorization().role().name()
                    + ", revision=" + result.authorization().revision() + ".");
        }
        if (publish) {
            mutation.result.complete(result);
        } else {
            mutation.result.completeExceptionally(new PlayerOperationException(Kind.UNAVAILABLE));
        }
    }

    private <T> Runnable resolveTargetOnWorker(TargetLookup<T> lookup) throws PersistenceException {
        T result = lookup.read.execute(repository);
        return () -> {
            boolean publish;
            synchronized (ownership) {
                // Keep the read registered until publication so stop/deadline can still win.
                publish = targetLookups.remove(lookup) && state == State.RUNNING;
                cancel(lookup.deadline);
            }
            if (publish) {
                lookup.result.complete(result);
            } else {
                lookup.result.completeExceptionally(new PlayerOperationException(Kind.UNAVAILABLE));
            }
        };
    }

    private <T> Runnable profileOnWorker(ProfileTask<T> task) throws PersistenceException {
        synchronized (ownership) {
            if (state != State.RUNNING || !profileConnection(task.entry)) {
                profileOperations.remove(task);
                cancel(task.deadline);
                return () -> task.result.completeExceptionally(new PlayerOperationException(Kind.CANCELLED));
            }
        }

        T result = task.action.execute(profiles, task.entry.attempt.identity().uuid());
        return () -> {
            boolean publish;
            synchronized (ownership) {
                publish = profileOperations.remove(task) && state == State.RUNNING && profileConnection(task.entry);
                cancel(task.deadline);
            }
            if (publish) {
                task.result.complete(result);
            } else {
                task.result.completeExceptionally(new PlayerOperationException(Kind.CANCELLED));
            }
        };
    }

    private void rejectProfileOperations() {
        List<ProfileTask<?>> pending;
        synchronized (ownership) {
            pending = List.copyOf(profileOperations);
            profileOperations.clear();
        }
        for (ProfileTask<?> task : pending) {
            task.result.completeExceptionally(new PlayerOperationException(Kind.UNAVAILABLE));
        }
    }

    private void rejectRoleChanges() {
        List<RoleMutation> pending;
        synchronized (ownership) {
            pending = List.copyOf(roleChanges);
            roleChanges.clear();
        }
        for (RoleMutation mutation : pending) {
            mutation.result.completeExceptionally(new PlayerOperationException(Kind.UNAVAILABLE));
        }
    }

    private void rejectTargetLookups() {
        List<TargetLookup<?>> pending;
        synchronized (ownership) {
            pending = List.copyOf(targetLookups);
            targetLookups.clear();
        }
        for (TargetLookup<?> lookup : pending) {
            lookup.result.completeExceptionally(new PlayerOperationException(Kind.UNAVAILABLE));
        }
    }

    private void requestOwnershipCheck() {
        try {
            synchronized (ownership) {
                if (state != State.RUNNING || ownershipCheckPending) {
                    return;
                }
                ownershipCheckPending = true;
                long sequence = ++ownershipCheckSequence;
                ownershipDeadline = deadlines.schedule(
                        () -> expireOwnershipCheck(sequence),
                        limits.ownershipTimeout().toNanos(), TimeUnit.NANOSECONDS);
            }
        } catch (RejectedExecutionException unavailableClock) {
            failService("Player deadline clock is unavailable.");
            return;
        }
        kick();
    }

    private void expireOwnershipCheck(long sequence) {
        failService("Player ownership check exceeded its deadline.",
                () -> ownershipCheckPending && ownershipCheckSequence == sequence);
    }

    private void failService(String message) {
        failService(message, () -> true);
    }

    /**
     * The predicate inspects only internal state, making deadline expiry atomic with completion.
     */
    private void failService(String message, BooleanSupplier stillApplicable) {
        List<Entry> pending;
        synchronized (ownership) {
            if ((state != State.STARTING && state != State.RUNNING) || !stillApplicable.getAsBoolean()) {
                return;
            }
            state = State.FAILED;
            stopTimers();
            mailbox.clear();
            pending = new ArrayList<>(attempts.values());
        }
        // Close runtime admission before exposing failures to arbitrary future continuations.
        try {
            criticalFailure.accept(message);
        } finally {
            rejectPending(pending);
            rejectRoleChanges();
            rejectTargetLookups();
            rejectProfileOperations();
        }
    }

    private void stopTimers() {
        cancel(ownershipPoll);
        cancel(ownershipDeadline);
        cancel(authorizationPoll);
        authorizationRefreshPending = false;
        ownershipCheckPending = false;
        for (Entry entry : attempts.values()) {
            entry.authorization = null;
            cancel(entry.deadline);
            cancel(entry.authorizationDeadline);
        }
        for (RoleMutation mutation : roleChanges) {
            cancel(mutation.deadline);
        }
        for (TargetLookup<?> lookup : targetLookups) {
            cancel(lookup.deadline);
        }
        for (ProfileTask<?> task : profileOperations) {
            cancel(task.deadline);
        }
    }

    private void rejectPending(List<Entry> pending) {
        for (Entry entry : pending) {
            PlayerOperationException failure = new PlayerOperationException(Kind.UNAVAILABLE);
            entry.preparation.completeExceptionally(failure);
            entry.activation.completeExceptionally(failure);
            entry.departure.completeExceptionally(failure);
        }
    }

    private void requireIoThread() {
        if (Thread.currentThread() != ioThread) {
            throw new IllegalStateException("Player persistence requires the runtime's I/O worker.");
        }
    }

    private enum State {
        STARTING, RUNNING, FAILED, STOPPING, CLOSED
    }

    private enum Phase {
        LOADING, PREPARED, JOINING, ACTIVE, CLOSING, CANCELLED
    }

    private enum Operation {
        LOAD, JOIN, QUIT, ROLE_CHANGE, COMMAND_AUDIT, TARGET_LOOKUP, PROFILE
    }

    @FunctionalInterface
    private interface ProfileOperation<T> {
        T execute(PlayerProfileRepository repository, UUID playerUuid) throws PersistenceException;
    }

    @FunctionalInterface
    private interface TargetRead<T> {
        T execute(PlayerSessionRepository repository) throws PersistenceException;
    }

    private record Work(Entry entry, Operation operation, RoleMutation mutation,
                        CommandAuditEvent commandAudit, TargetLookup<?> targetLookup, ProfileTask<?> profileTask) {
        private Work(Entry entry, Operation operation, RoleMutation mutation,
                     CommandAuditEvent commandAudit, TargetLookup<?> targetLookup) {
            this(entry, operation, mutation, commandAudit, targetLookup, null);
        }

        private Work(Entry entry, Operation operation) {
            this(entry, operation, null, null, null);
        }

        private Work(Entry entry, Operation operation, RoleMutation mutation) {
            this(entry, operation, mutation, null, null);
        }

        private Work(Entry entry, Operation operation, RoleMutation mutation, CommandAuditEvent commandAudit) {
            this(entry, operation, mutation, commandAudit, null);
        }
    }

    private static final class TargetLookup<T> {
        private final TargetRead<T> read;
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private ScheduledFuture<?> deadline;

        private TargetLookup(TargetRead<T> read) {
            this.read = read;
        }
    }

    private static final class ProfileTask<T> {
        private final Entry entry;
        private final ProfileOperation<T> action;
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private ScheduledFuture<?> deadline;

        private ProfileTask(Entry entry, ProfileOperation<T> action) {
            this.entry = entry;
            this.action = action;
        }
    }

    private static final class RoleMutation {
        private final RoleChangeRequest request;
        private final CompletableFuture<RoleChangeResult> result = new CompletableFuture<>();
        private ScheduledFuture<?> deadline;

        private RoleMutation(RoleChangeRequest request) {
            this.request = request;
        }
    }

    private static final class Entry {
        private final PlayerAttempt attempt;
        private final CompletableFuture<PreparedPlayer> preparation = new CompletableFuture<>();
        private final CompletableFuture<PlayerProfile> activation = new CompletableFuture<>();
        private final CompletableFuture<Void> departure = new CompletableFuture<>();
        private long queuedAtNanos;
        private Phase phase = Phase.LOADING;
        private AuthorizationSnapshot authorization;
        private boolean authorizationExpired;
        private boolean persisted;
        private long authorizationVerifiedAt;
        private long authorizationSequence;
        private ScheduledFuture<?> authorizationDeadline;
        private ScheduledFuture<?> deadline;
        private Instant joinedAt;
        private Instant leftAt;
        private boolean committed;

        private Entry(PlayerAttempt attempt) {
            this.attempt = attempt;
        }
    }
}
