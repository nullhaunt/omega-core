package com.omegarealm.core.player;

import com.omegarealm.core.authorization.AuthorizationSnapshot;

import java.time.Clock;
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
public final class PlayerService {

    private final Object ownership = new Object();
    private final PlayerSessionRepository repository;
    private final Executor worker;
    private final ScheduledExecutorService deadlines;
    private final Consumer<String> criticalFailure;
    private final Logger logger;
    private final Clock time;
    private final PlayerServiceLimits limits;
    private final Thread ioThread;
    private final Map<UUID, Entry> attempts = new HashMap<>();
    private final Map<UUID, Entry> currentPlayers = new HashMap<>();
    private final ArrayDeque<Work> mailbox = new ArrayDeque<>();
    private State state = State.STARTING;
    private boolean pumpScheduled;
    private Work inFlight;
    private boolean ownershipCheckPending;
    private long ownershipCheckSequence;
    private ScheduledFuture<?> ownershipPoll;
    private ScheduledFuture<?> ownershipDeadline;
    private Instant stoppedAt;
    private long cleanupDeadline;
    private boolean cleanlyClosed = true;

    /**
     * Construct and initialize on the same worker that acquired the repository.
     */
    public PlayerService(PlayerSessionRepository repository, Executor worker,
                         ScheduledExecutorService deadlines, Consumer<String> criticalFailure,
                         Logger logger, Clock time, PlayerServiceLimits limits) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
        this.criticalFailure = Objects.requireNonNull(criticalFailure, "criticalFailure");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.time = Objects.requireNonNull(time, "time");
        this.limits = Objects.requireNonNull(limits, "limits");
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
            ownershipPoll = deadlines.scheduleWithFixedDelay(this::requestOwnershipCheck,
                    limits.ownershipInterval().toNanos(), limits.ownershipInterval().toNanos(),
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
     * Readiness is an in-memory token check, suitable for a later login validation hook.
     */
    public boolean isPrepared(UUID token) {
        synchronized (ownership) {
            Entry entry = attempts.get(token);
            return state == State.RUNNING && entry != null && entry.phase == Phase.PREPARED;
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
            if (state != State.RUNNING || entry == null
                    || (entry.phase != Phase.PREPARED && entry.phase != Phase.JOINING && entry.phase != Phase.ACTIVE)) {
                return Optional.empty();
            }
            return Optional.ofNullable(entry.authorization);
        }
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
            mailbox.clear();
            pending = new ArrayList<>(attempts.values());
        }
        rejectPending(pending);
    }

    /**
     * Shutdown-only resource cleanup. Runtime closes the pool after this returns.
     */
    public boolean closeOnWorker() {
        requireIoThread();
        requestStop(time.instant());
        List<Entry> draining;
        synchronized (ownership) {
            if (state == State.CLOSED) {
                return cleanlyClosed;
            }
            if (inFlight != null) {
                throw new IllegalStateException("Player cleanup cannot run inside a mailbox operation.");
            }
            draining = new ArrayList<>(attempts.values());
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
            if (state != State.RUNNING || pumpScheduled || (mailbox.isEmpty() && !ownershipCheckPending)) {
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
            synchronized (ownership) {
                if (state != State.RUNNING) {
                    pumpScheduled = false;
                    return;
                }
                checkOwner = ownershipCheckPending;
                work = checkOwner ? null : mailbox.pollFirst();
                if (!checkOwner && work == null) {
                    pumpScheduled = false;
                    return;
                }
                inFlight = work;
            }

            try {
                if (checkOwner) {
                    repository.checkOwnership();
                    synchronized (ownership) {
                        ownershipCheckPending = false;
                        cancel(ownershipDeadline);
                        ownershipDeadline = null;
                    }
                } else {
                    switch (work.operation()) {
                        case LOAD -> load(work.entry());
                        case JOIN -> join(work.entry());
                        case QUIT -> quit(work.entry());
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
        }
    }

    private void load(Entry entry) throws PersistenceException {
        synchronized (ownership) {
            if (entry.phase != Phase.LOADING) {
                return;
            }
        }
        LoadedPlayer loaded = repository.loadPlayer(entry.attempt.identity().uuid());
        PreparedPlayer prepared = new PreparedPlayer(entry.attempt, loaded.storedProfile(), loaded.authorization());
        synchronized (ownership) {
            if (state != State.RUNNING || entry.phase != Phase.LOADING) {
                return;
            }
            cancel(entry.deadline);
            entry.authorization = prepared.authorization();
            entry.phase = Phase.PREPARED;
            entry.deadline = deadlines.schedule(() -> expire(entry, Phase.PREPARED),
                    limits.preparedTimeout().toNanos(), TimeUnit.NANOSECONDS);
        }
        entry.preparation.complete(prepared);
    }

    private void join(Entry entry) throws PersistenceException {
        PlayerProfile profile = repository.join(sessionStart(entry));
        boolean publish;
        synchronized (ownership) {
            entry.committed = true;
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
        cancel(entry.deadline);
        attempts.remove(entry.attempt.token(), entry);
        currentPlayers.remove(entry.attempt.identity().uuid(), entry);
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
        }
    }

    private void stopTimers() {
        cancel(ownershipPoll);
        cancel(ownershipDeadline);
        ownershipCheckPending = false;
        for (Entry entry : attempts.values()) {
            entry.authorization = null;
            cancel(entry.deadline);
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
        LOAD, JOIN, QUIT
    }

    private record Work(Entry entry, Operation operation) {
    }

    private static final class Entry {
        private final PlayerAttempt attempt;
        private final CompletableFuture<PreparedPlayer> preparation = new CompletableFuture<>();
        private final CompletableFuture<PlayerProfile> activation = new CompletableFuture<>();
        private final CompletableFuture<Void> departure = new CompletableFuture<>();
        private Phase phase = Phase.LOADING;
        private AuthorizationSnapshot authorization;
        private ScheduledFuture<?> deadline;
        private Instant joinedAt;
        private Instant leftAt;
        private boolean committed;

        private Entry(PlayerAttempt attempt) {
            this.attempt = attempt;
        }
    }
}
