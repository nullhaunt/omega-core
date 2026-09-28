package com.omegarealm.core.player;

import com.omegarealm.core.authorization.*;
import com.omegarealm.core.command.CommandAuditEvent;
import com.omegarealm.core.command.CommandAuditEvent.Category;
import com.omegarealm.core.command.CommandAuditEvent.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

import static com.omegarealm.core.player.PlayerOperationException.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class PlayerServiceTest {

    private static final Instant JOINED = Instant.parse("2026-09-26T12:00:00Z");
    private static final PlayerServiceLimits LIMITS = limits(64, 256, Duration.ofSeconds(3),
            Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(30), Duration.ofSeconds(3));

    private static PlayerServiceLimits limits(int pending, int attempts, Duration load, Duration prepared,
                                              Duration mutation, Duration polling, Duration ownerDeadline) {
        return new PlayerServiceLimits(pending, attempts, load, prepared, mutation, polling,
                ownerDeadline, Duration.ofSeconds(3));
    }

    private static PlayerServiceLimits pollingLimits() {
        return limits(64, 256, Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(3),
                Duration.ofMillis(100), Duration.ofMillis(150));
    }

    private static PlayerAttempt attempt() {
        return new PlayerAttempt(UUID.randomUUID(), new PlayerIdentity(UUID.randomUUID(), "Player"));
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private static void assertFailure(PlayerOperationException.Kind kind, CompletionStage<?> stage) {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> stage.toCompletableFuture().get(2, TimeUnit.SECONDS));
        PlayerOperationException controlled = assertInstanceOf(PlayerOperationException.class, failure.getCause());
        assertEquals(kind, controlled.kind());
        assertNull(controlled.getCause());
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean());
    }

    private static AuthorizationRefreshLimits refreshLimits() {
        return new AuthorizationRefreshLimits(Duration.ofMillis(40), Duration.ofMillis(300), 2);
    }

    private static CommandAuditEvent invocation(String path) {
        return new CommandAuditEvent(UUID.randomUUID(), new AuthorizationActor.LocalConsole(),
                Category.MANAGEMENT, path, Outcome.INVOKED, null, null, null);
    }

    @Test
    void commandAuditQueueRejectsOverflowBeforeExecution() throws Exception {
        FakeRepository repository = new FakeRepository();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PlayerServiceLimits bounded = limits(1, 256, Duration.ofSeconds(3), Duration.ofSeconds(3),
                Duration.ofSeconds(3), Duration.ofSeconds(30), Duration.ofSeconds(3));

        try (Harness harness = new Harness(repository, bounded)) {
            harness.worker.execute(() -> {
                entered.countDown();
                waitFor(release);
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            CommandAuditEvent accepted = invocation("time.set");
            assertTrue(harness.service.queueCommandAudit(accepted));
            assertFalse(harness.service.queueCommandAudit(invocation("weather.clear")));
            release.countDown();
            harness.barrier();
            assertEquals(List.of(accepted), repository.audits);
            assertTrue(harness.failures.isEmpty());
        } finally {
            release.countDown();
        }
    }

    @Test
    void orderlyStopDrainsAcceptedCommandAudit() throws Exception {
        FakeRepository repository = new FakeRepository();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CommandAuditEvent accepted = invocation("save-all");

        try (Harness harness = new Harness(repository, LIMITS)) {
            harness.worker.execute(() -> {
                entered.countDown();
                waitFor(release);
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(harness.service.queueCommandAudit(accepted));
            harness.service.requestStop(JOINED.plusSeconds(3600));
            assertFalse(harness.service.queueCommandAudit(invocation("time.set")));
            release.countDown();
        } finally {
            release.countDown();
        }
        assertEquals(List.of(accepted), repository.audits);
        assertEquals(List.of("recover", "audit", "close"), repository.events);
    }

    @Test
    void failedCommandAuditWriteFailsTheServiceClosed() throws Exception {
        FakeRepository repository = new FakeRepository();
        repository.failAudit = true;

        try (Harness harness = new Harness(repository, LIMITS)) {
            assertTrue(harness.service.queueCommandAudit(invocation("weather.clear")));
            harness.barrier();

            assertFalse(harness.service.isRunning());
            assertEquals(1, harness.failures.size());
            assertFalse(harness.service.queueCommandAudit(invocation("time.set")));
        }
    }

    @Test
    void loadedAuthorityRemainsConnectionOwnedAndQuitRemovesItBeforePersistence() throws Exception {
        FakeRepository repository = new FakeRepository();
        repository.read = uuid -> Optional.of(new PlayerProfile(uuid, "Player", JOINED, JOINED));
        repository.authorization = uuid -> new AuthorizationSnapshot(uuid, Role.ADMIN, 7);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repository.end = () -> {
            entered.countDown();
            waitFor(release);
        };

        try (Harness harness = new Harness(repository, LIMITS)) {
            PlayerAttempt player = attempt();
            PreparedPlayer prepared = await(harness.service.prepare(player));
            assertEquals(Role.ADMIN, prepared.authorization().role());
            assertEquals(7, prepared.authorization().revision());
            assertEquals(prepared.authorization(), harness.service.authorization(player.token()).orElseThrow());
            assertTrue(harness.service.authorization(player.identity().uuid()).isEmpty());
            await(harness.service.activate(player.token(), JOINED));
            assertEquals(prepared.authorization(), harness.service.authorization(player.token()).orElseThrow());
            CompletionStage<Void> quitting = harness.service.disconnect(player.token(), JOINED.plusSeconds(1));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertTrue(harness.service.authorization(player.token()).isEmpty());
            } finally {
                release.countDown();
            }
            await(quitting);
            assertTrue(harness.service.authorization(player.token()).isEmpty());
        }
    }

    @Test
    void disconnectDuringAuthorizationLoadCannotPublishOldRoleOverReconnect() throws Exception {
        FakeRepository repository = new FakeRepository();
        repository.read = uuid -> Optional.of(new PlayerProfile(uuid, "Player", JOINED, JOINED));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repository.authorization = uuid -> {
            if (repository.reads.get() == 1) {
                entered.countDown();
                waitFor(release);
                return new AuthorizationSnapshot(uuid, Role.OWNER, 3);
            }
            return new AuthorizationSnapshot(uuid, Role.PLAYER, 4);
        };

        try (Harness harness = new Harness(repository, LIMITS)) {
            PlayerAttempt old = attempt();
            CompletionStage<PreparedPlayer> loading = harness.service.prepare(old);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertFalse(harness.service.isPrepared(old.token()));
                assertTrue(harness.service.authorization(old.token()).isEmpty());
                await(harness.service.disconnect(old.token(), JOINED));
                assertFailure(CANCELLED, loading);
                PlayerAttempt reconnect = new PlayerAttempt(UUID.randomUUID(), old.identity());
                CompletionStage<PreparedPlayer> current = harness.service.prepare(reconnect);
                release.countDown();
                PreparedPlayer prepared = await(current);
                assertEquals(Role.PLAYER, prepared.authorization().role());
                assertEquals(4, prepared.authorization().revision());
                assertTrue(harness.service.authorization(old.token()).isEmpty());
                assertEquals(prepared.authorization(), harness.service.authorization(reconnect.token()).orElseThrow());
                assertEquals(0, repository.joins.size());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void authorizationLoadSharesPreparationDeadlineAndLateResultIsDiscarded() throws Exception {
        FakeRepository repository = new FakeRepository();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repository.authorization = uuid -> {
            entered.countDown();
            waitFor(release);
            return AuthorizationSnapshot.initial(uuid);
        };
        try (Harness harness = new Harness(repository, LIMITS)) {
            PlayerAttempt player = attempt();
            CompletionStage<PreparedPlayer> loading = harness.service.prepare(player);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                harness.clock.lastDeadline.run();
                assertFailure(TIMED_OUT, loading);
                release.countDown();
                harness.barrier();
                assertFalse(harness.service.isPrepared(player.token()));
                assertTrue(harness.service.authorization(player.token()).isEmpty());
                assertEquals(0, repository.joins.size());
                assertTrue(harness.failures.isEmpty());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void shutdownDuringAuthorizationLoadCannotPublishLateAuthority() throws Exception {
        FakeRepository repository = new FakeRepository();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repository.authorization = uuid -> {
            entered.countDown();
            waitFor(release);
            return AuthorizationSnapshot.initial(uuid);
        };
        try (Harness harness = new Harness(repository, LIMITS)) {
            PlayerAttempt player = attempt();
            CompletionStage<PreparedPlayer> loading = harness.service.prepare(player);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                harness.service.requestStop(JOINED);
                assertFailure(UNAVAILABLE, loading);
                release.countDown();
                harness.barrier();
                assertTrue(harness.service.authorization(player.token()).isEmpty());
                assertFalse(harness.service.isPrepared(player.token()));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void authorizationFailureClosesAllSnapshotAccessAndOmitsRawDiagnostic() throws Exception {
        FakeRepository repository = new FakeRepository();
        try (Harness harness = new Harness(repository, LIMITS)) {
            PlayerAttempt active = attempt();
            await(harness.service.prepare(active));
            await(harness.service.activate(active.token(), JOINED));
            assertTrue(harness.service.authorization(active.token()).isPresent());

            repository.authorization = uuid -> {
                throw new IllegalStateException("DO_NOT_LOG_THIS_SECRET");
            };
            assertFailure(UNAVAILABLE, harness.service.prepare(attempt()));
            assertFalse(harness.service.isRunning());
            assertTrue(harness.service.authorization(active.token()).isEmpty());
            assertEquals(1, harness.failures.size());
            assertFalse(harness.failures.getFirst().contains("DO_NOT_LOG"));
        }
    }

    @Test
    void mismatchedLoadedAuthorizationCannotPrepareAConnection() throws Exception {
        FakeRepository repository = new FakeRepository();
        repository.authorization = uuid -> AuthorizationSnapshot.initial(UUID.randomUUID());
        try (Harness harness = new Harness(repository, LIMITS)) {
            PlayerAttempt player = attempt();
            assertFailure(UNAVAILABLE, harness.service.prepare(player));
            assertFalse(harness.service.isPrepared(player.token()));
            assertTrue(harness.service.authorization(player.token()).isEmpty());
            assertEquals(1, harness.failures.size());
        }
    }

    @Test
    void preparationLoadsOffCallerThreadWithoutWritingAndCachesReturningMetadata() throws Exception {
        FakeRepository repository = new FakeRepository();
        PlayerAttempt attempt = attempt();
        PlayerProfile returning = new PlayerProfile(attempt.identity().uuid(), "PreviousName", JOINED, JOINED);
        repository.read = uuid -> Optional.of(returning);
        try (Harness harness = new Harness(repository, LIMITS)) {
            assertEquals(1, repository.recoveries.get());
            PreparedPlayer prepared = await(harness.service.prepare(attempt));
            assertEquals(attempt, prepared.attempt());
            assertEquals(returning, prepared.storedProfile().orElseThrow());
            assertTrue(harness.service.isPrepared(attempt.token()));
            assertNotSame(Thread.currentThread(), repository.owner);
            assertEquals(0, repository.joins.size());
            assertEquals(0, repository.ends.size());

            PreparedPlayer repeated = await(harness.service.prepare(attempt));
            assertEquals(prepared, repeated);
            assertEquals(1, repository.reads.get());
        }
    }

    @Test
    void disconnectDuringReadCannotPublishOverReconnect() throws Exception {
        FakeRepository repository = new FakeRepository();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repository.read = uuid -> {
            if (repository.reads.get() == 1) {
                entered.countDown();
                waitFor(release);
            }
            return Optional.empty();
        };
        try (Harness harness = new Harness(repository, LIMITS)) {
            PlayerAttempt old = attempt();
            CompletionStage<PreparedPlayer> loading = harness.service.prepare(old);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                await(harness.service.disconnect(old.token(), JOINED));
                assertFailure(CANCELLED, loading);
                PlayerAttempt reconnect = new PlayerAttempt(UUID.randomUUID(), old.identity());
                CompletionStage<PreparedPlayer> newLoad = harness.service.prepare(reconnect);
                release.countDown();
                await(newLoad);
                assertFalse(harness.service.isPrepared(old.token()));
                assertTrue(harness.service.isPrepared(reconnect.token()));
                assertEquals(0, repository.joins.size());
                assertTrue(harness.failures.isEmpty());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void loadTimeoutRejectsAttemptAndDiscardsItsLateResult() throws Exception {
        FakeRepository repository = new FakeRepository();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repository.read = uuid -> {
            if (repository.reads.get() == 1) {
                entered.countDown();
                waitFor(release);
            }
            return Optional.empty();
        };
        PlayerServiceLimits shortLoad = limits(64, 256, Duration.ofMillis(150), Duration.ofSeconds(3),
                Duration.ofSeconds(3), Duration.ofSeconds(30), Duration.ofSeconds(3));
        try (Harness harness = new Harness(repository, shortLoad)) {
            PlayerAttempt old = attempt();
            CompletionStage<PreparedPlayer> loading = harness.service.prepare(old);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertFailure(TIMED_OUT, loading);
                release.countDown();
                PlayerAttempt reconnect = new PlayerAttempt(UUID.randomUUID(), old.identity());
                await(harness.service.prepare(reconnect));
                assertFalse(harness.service.isPrepared(old.token()));
                assertTrue(harness.service.isPrepared(reconnect.token()));
                assertTrue(harness.failures.isEmpty());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void abandonedPreparedAttemptExpiresAndReleasesCapacity() throws Exception {
        PlayerServiceLimits shortPreparation = limits(64, 1, Duration.ofSeconds(3), Duration.ofMillis(150),
                Duration.ofSeconds(3), Duration.ofSeconds(30), Duration.ofSeconds(3));
        FakeRepository repository = new FakeRepository();
        try (Harness harness = new Harness(repository, shortPreparation)) {
            PlayerAttempt abandoned = attempt();
            await(harness.service.prepare(abandoned));
            assertTrue(harness.service.authorization(abandoned.token()).isPresent());
            assertFailure(BUSY, harness.service.prepare(attempt()));
            waitUntil(() -> !harness.service.isPrepared(abandoned.token()));
            assertTrue(harness.service.authorization(abandoned.token()).isEmpty());
            await(harness.service.prepare(new PlayerAttempt(UUID.randomUUID(), abandoned.identity())));
            assertEquals(0, repository.joins.size());
        }
    }

    @Test
    void duplicateIdentityAndTokenCollisionAreRejectedWithoutFailingService() throws Exception {
        FakeRepository repository = new FakeRepository();
        try (Harness harness = new Harness(repository, LIMITS)) {
            PlayerAttempt first = attempt();
            await(harness.service.prepare(first));
            assertFailure(DUPLICATE, harness.service.prepare(new PlayerAttempt(UUID.randomUUID(), first.identity())));
            assertFailure(DUPLICATE, harness.service.prepare(new PlayerAttempt(first.token(), attempt().identity())));
            await(harness.service.activate(first.token(), JOINED));
            assertFailure(DUPLICATE, harness.service.prepare(new PlayerAttempt(UUID.randomUUID(), first.identity())));
            assertTrue(harness.service.isRunning());
            assertTrue(harness.failures.isEmpty());
        }
    }

    @Test
    void quitAndReconnectStayOrderedWhileJoinIsBlocked() throws Exception {
        FakeRepository repository = new FakeRepository();
        CountDownLatch joined = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repository.join = start -> {
            if (repository.joins.size() == 1) {
                joined.countDown();
                waitFor(release);
            }
        };
        try (Harness harness = new Harness(repository, LIMITS)) {
            PlayerAttempt first = attempt();
            await(harness.service.prepare(first));
            CompletionStage<PlayerProfile> activation = harness.service.activate(first.token(), JOINED);
            try {
                assertTrue(joined.await(2, TimeUnit.SECONDS));
                CompletionStage<Void> quit = harness.service.disconnect(first.token(), JOINED.plusSeconds(10));
                PlayerAttempt reconnect = new PlayerAttempt(UUID.randomUUID(), first.identity());
                CompletionStage<PreparedPlayer> loading = harness.service.prepare(reconnect);
                assertFailure(CANCELLED, activation);
                release.countDown();
                await(quit);
                await(loading);
                await(harness.service.activate(reconnect.token(), JOINED.plusSeconds(20)));
                await(harness.service.disconnect(first.token(), JOINED.plusSeconds(30)));
                assertEquals(List.of("recover", "load", "join", "quit", "load", "join"), repository.events);
                assertEquals(1, repository.ends.size());
                assertEquals(first.token(), repository.ends.getFirst().id());
                assertTrue(harness.failures.isEmpty());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void alreadyDispatchedQuitDeadlineCannotFailServiceAfterSuccessfulClosure() throws Exception {
        FakeRepository repository = new FakeRepository();
        try (Harness harness = new Harness(repository, LIMITS)) {
            PlayerAttempt player = attempt();
            await(harness.service.prepare(player));
            await(harness.service.activate(player.token(), JOINED));
            await(harness.service.disconnect(player.token(), JOINED.plusSeconds(10)));

            // Cancellation cannot stop a clock callback that was already dispatched.
            harness.clock.lastDeadline.run();
            assertTrue(harness.service.isRunning());
            assertTrue(harness.failures.isEmpty());
            await(harness.service.prepare(new PlayerAttempt(UUID.randomUUID(), player.identity())));
        }
    }

    @Test
    void mailboxSaturationRejectsAdditionalLoadsWithoutWriting() throws Exception {
        FakeRepository repository = new FakeRepository();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repository.read = uuid -> {
            entered.countDown();
            waitFor(release);
            return Optional.empty();
        };
        PlayerServiceLimits small = limits(1, 256, Duration.ofSeconds(3), Duration.ofSeconds(3),
                Duration.ofSeconds(3), Duration.ofSeconds(30), Duration.ofSeconds(3));
        try (Harness harness = new Harness(repository, small)) {
            CompletionStage<PreparedPlayer> first = harness.service.prepare(attempt());
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertFailure(BUSY, harness.service.prepare(attempt()));
                assertTrue(harness.failures.isEmpty());
                release.countDown();
                await(first);
                assertEquals(1, repository.reads.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void mutationSaturationFailsClosedAndPreservesObservedJoinForCleanup() throws Exception {
        FakeRepository repository = new FakeRepository();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PlayerServiceLimits small = limits(1, 256, Duration.ofSeconds(3), Duration.ofSeconds(3),
                Duration.ofSeconds(3), Duration.ofSeconds(30), Duration.ofSeconds(3));
        Harness harness = new Harness(repository, small);
        try {
            PlayerAttempt observedJoin = attempt();
            await(harness.service.prepare(observedJoin));
            repository.read = uuid -> {
                entered.countDown();
                waitFor(release);
                return Optional.empty();
            };
            harness.service.prepare(attempt());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFailure(UNAVAILABLE, harness.service.activate(observedJoin.token(), JOINED));
            assertEquals(1, harness.failures.size());
            assertFalse(harness.service.isRunning());
        } finally {
            release.countDown();
            harness.close();
        }
        assertEquals(1, repository.joins.size());
        assertEquals(1, repository.ends.size());
        assertEquals(SessionEnd.SHUTDOWN, repository.ends.getFirst().reason());
    }

    @Test
    void unexpectedReadFailureIsSanitizedAndDoesNotLeaveServiceUsable() throws Exception {
        FakeRepository repository = new FakeRepository();
        repository.read = uuid -> {
            throw new IllegalStateException("DO_NOT_LOG_THIS_SECRET");
        };
        try (Harness harness = new Harness(repository, LIMITS)) {
            assertFailure(UNAVAILABLE, harness.service.prepare(attempt()));
            waitUntil(() -> !harness.failures.isEmpty());
            assertFalse(harness.failures.getFirst().contains("DO_NOT_LOG"));
            assertFailure(UNAVAILABLE, harness.service.prepare(attempt()));
            assertFalse(harness.service.isRunning());
        }
    }

    @Test
    void ownershipLossWhileIdleFailsServiceOnce() throws Exception {
        FakeRepository repository = new FakeRepository();
        repository.ownerCheck = () -> {
            throw new IllegalStateException("DO_NOT_LOG_THIS_SECRET");
        };
        try (Harness harness = new Harness(repository, pollingLimits())) {
            waitUntil(() -> !harness.failures.isEmpty());
            assertEquals(1, harness.failures.size());
            assertFalse(harness.failures.getFirst().contains("DO_NOT_LOG"));
            assertFailure(UNAVAILABLE, harness.service.prepare(attempt()));
            assertEquals(0, repository.reads.get());
        }
    }

    @Test
    void ownershipCheckTimeoutCannotBeReversedByLateSuccess() throws Exception {
        FakeRepository repository = new FakeRepository();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repository.ownerCheck = () -> {
            entered.countDown();
            waitFor(release);
        };
        try (Harness harness = new Harness(repository, pollingLimits())) {
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                waitUntil(() -> !harness.failures.isEmpty());
                release.countDown();
                harness.barrier();
                assertFalse(harness.service.isRunning());
                assertEquals(1, harness.failures.size());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void mutationTimeoutDoesNotPublishLateCommitAndCleanupClosesIt() throws Exception {
        FakeRepository repository = new FakeRepository();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repository.join = start -> {
            entered.countDown();
            waitFor(release);
        };
        PlayerServiceLimits shortMutation = limits(64, 256, Duration.ofSeconds(3), Duration.ofSeconds(3),
                Duration.ofMillis(150), Duration.ofSeconds(30), Duration.ofSeconds(3));
        Harness harness = new Harness(repository, shortMutation);
        try {
            PlayerAttempt player = attempt();
            await(harness.service.prepare(player));
            CompletionStage<PlayerProfile> activation = harness.service.activate(player.token(), JOINED);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFailure(UNAVAILABLE, activation);
            assertFalse(harness.service.isRunning());
            release.countDown();
        } finally {
            release.countDown();
            harness.close();
        }
        assertEquals(1, repository.joins.size());
        assertEquals(1, repository.ends.size());
        assertEquals(SessionEnd.SHUTDOWN, repository.ends.getFirst().reason());
    }

    @Test
    void shutdownDiscardsLateLoadAndClosesInFlightJoinBeforeRepository() throws Exception {
        FakeRepository repository = new FakeRepository();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repository.join = start -> {
            entered.countDown();
            waitFor(release);
        };
        Harness harness = new Harness(repository, LIMITS);
        try {
            PlayerAttempt player = attempt();
            await(harness.service.prepare(player));
            CompletionStage<PlayerProfile> activation = harness.service.activate(player.token(), JOINED);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            CompletionStage<PreparedPlayer> queuedLoad = harness.service.prepare(attempt());
            harness.service.requestStop(JOINED.plusSeconds(60));
            assertFailure(UNAVAILABLE, activation);
            assertFailure(UNAVAILABLE, queuedLoad);
            assertFalse(harness.service.isPrepared(player.token()));
        } finally {
            release.countDown();
            harness.close();
        }
        assertEquals(1, repository.reads.get());
        assertEquals(List.of("recover", "load", "join", "shutdown", "close"), repository.events);
        assertEquals(JOINED.plusSeconds(60), repository.ends.getFirst().at());
    }

    @Test
    void shutdownDrainsAcceptedJoinEvenIfItsMailboxWorkNeverStarted() throws Exception {
        FakeRepository repository = new FakeRepository();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Harness harness = new Harness(repository, LIMITS);
        try {
            PlayerAttempt player = attempt();
            await(harness.service.prepare(player));
            harness.worker.execute(() -> {
                held.countDown();
                waitFor(release);
            });
            assertTrue(held.await(2, TimeUnit.SECONDS));
            CompletionStage<PlayerProfile> queuedJoin = harness.service.activate(player.token(), JOINED);
            harness.service.requestStop(JOINED.plusSeconds(30));
            assertFailure(UNAVAILABLE, queuedJoin);
        } finally {
            release.countDown();
            harness.close();
        }
        assertEquals(1, repository.joins.size());
        assertEquals(1, repository.ends.size());
        assertEquals(JOINED.plusSeconds(30), repository.ends.getFirst().at());
    }

    @Test
    void cleanupBudgetStopsAdditionalWritesButStillClosesRepository() throws Exception {
        FakeRepository repository = new FakeRepository();
        PlayerServiceLimits shortCleanup = new PlayerServiceLimits(64, 256, Duration.ofSeconds(3),
                Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(30),
                Duration.ofSeconds(3), Duration.ofMillis(100));
        Harness harness = new Harness(repository, shortCleanup);
        try {
            for (int index = 0; index < 2; index++) {
                PlayerAttempt player = attempt();
                await(harness.service.prepare(player));
                await(harness.service.activate(player.token(), JOINED));
            }
            repository.end = () -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            };
            harness.service.requestStop(JOINED.plusSeconds(30));
            assertFalse(harness.worker.submit(harness.service::closeOnWorker).get(2, TimeUnit.SECONDS));
            assertEquals(1, repository.ends.size());
            assertEquals("close", repository.events.getLast());
        } finally {
            harness.close();
        }
    }

    @Test
    void workerRejectionFailsServiceWithoutLeakingPendingFuture() throws Exception {
        FakeRepository repository = new FakeRepository();
        try (Harness harness = new Harness(repository, LIMITS,
                task -> {
                    throw new RejectedExecutionException("DO_NOT_LOG_THIS_SECRET");
                })) {
            assertFailure(UNAVAILABLE, harness.service.prepare(attempt()));
            assertEquals(1, harness.failures.size());
            assertFalse(harness.failures.getFirst().contains("DO_NOT_LOG"));
            assertEquals(0, repository.reads.get());
        }
    }

    @Test
    void shutdownCancelsAllTimersAndRejectsFurtherAttempts() throws Exception {
        FakeRepository repository = new FakeRepository();
        try (Harness harness = new Harness(repository, LIMITS)) {
            PlayerAttempt player = attempt();
            await(harness.service.prepare(player));
            harness.service.requestStop(JOINED.plusSeconds(30));
            assertFalse(harness.service.isPrepared(player.token()));
            assertTrue(harness.service.authorization(player.token()).isEmpty());
            assertFailure(UNAVAILABLE, harness.service.prepare(attempt()));
            assertTrue(harness.clock.getQueue().isEmpty());
            assertEquals(0, repository.joins.size());
        }
    }

    @Test
    void refreshBatchesConnectedPlayersAndPublishesHigherRevisions() throws Exception {
        FakeRepository repository = new FakeRepository();
        List<Integer> sizes = new CopyOnWriteArrayList<>();
        repository.authorizationBatch = uuids -> {
            sizes.add(uuids.size());
            return uuids.stream().collect(java.util.stream.Collectors.toMap(uuid -> uuid,
                    uuid -> new AuthorizationSnapshot(uuid, Role.ADMIN, 1)));
        };
        try (Harness harness = new Harness(repository, LIMITS, null, refreshLimits())) {
            List<PlayerAttempt> connected = List.of(attempt(), attempt(), attempt(), attempt(), attempt());
            for (PlayerAttempt player : connected) {
                await(harness.service.prepare(player));
            }
            waitUntil(() -> connected.stream().allMatch(player -> harness.service.authorization(player.token())
                    .map(snapshot -> snapshot.role() == Role.ADMIN && snapshot.revision() == 1).orElse(false)));
            assertTrue(sizes.size() >= 3);
            assertTrue(sizes.stream().allMatch(size -> size <= 2));
            assertTrue(harness.failures.isEmpty());
        }
    }

    @Test
    void delayedRefreshExpiresAuthorityAndCannotReviveItAfterCompletion() throws Exception {
        FakeRepository repository = new FakeRepository();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repository.authorizationBatch = uuids -> {
            entered.countDown();
            waitFor(release);
            return uuids.stream().collect(java.util.stream.Collectors.toMap(uuid -> uuid,
                    uuid -> new AuthorizationSnapshot(uuid, Role.OWNER, 1)));
        };
        try (Harness harness = new Harness(repository, LIMITS, null, refreshLimits())) {
            PlayerAttempt player = attempt();
            await(harness.service.prepare(player));
            await(harness.service.activate(player.token(), JOINED));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                waitUntil(() -> harness.service.requiresAuthorizationReconnect(player.token()));
                assertTrue(harness.service.authorization(player.token()).isEmpty());
            } finally {
                release.countDown();
            }
            harness.barrier();
            assertTrue(harness.service.authorization(player.token()).isEmpty());
            assertTrue(harness.service.requiresAuthorizationReconnect(player.token()));
            assertTrue(harness.service.isRunning());
            assertTrue(harness.failures.isEmpty());
        }
    }

    @Test
    void remainingBatchesDoNotReadPlayersWhoDisconnectedDuringAnEarlierBatch() throws Exception {
        FakeRepository repository = new FakeRepository();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<UUID> first = new java.util.concurrent.atomic.AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        repository.authorizationBatch = uuids -> {
            calls.incrementAndGet();
            first.set(uuids.getFirst());
            entered.countDown();
            waitFor(release);
            return Map.of(uuids.getFirst(), AuthorizationSnapshot.initial(uuids.getFirst()));
        };
        var refresh = new AuthorizationRefreshLimits(Duration.ofMillis(200), Duration.ofSeconds(2), 1);
        try (Harness harness = new Harness(repository, LIMITS, null, refresh)) {
            List<PlayerAttempt> connected = List.of(attempt(), attempt(), attempt());
            for (PlayerAttempt player : connected) {
                await(harness.service.prepare(player));
            }
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                for (PlayerAttempt player : connected) {
                    if (!player.identity().uuid().equals(first.get())) {
                        await(harness.service.disconnect(player.token(), JOINED.plusSeconds(1)));
                    }
                }
            } finally {
                release.countDown();
            }
            harness.barrier();
            assertEquals(1, calls.get());
            assertTrue(harness.failures.isEmpty());
        }
    }

    @Test
    void refreshForDepartedTokenCannotOverwriteReconnect() throws Exception {
        FakeRepository repository = new FakeRepository();
        repository.read = uuid -> Optional.of(new PlayerProfile(uuid, "Player", JOINED, JOINED));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger batches = new AtomicInteger();
        repository.authorizationBatch = uuids -> {
            if (batches.incrementAndGet() == 1) {
                entered.countDown();
                waitFor(release);
                return uuids.stream().collect(java.util.stream.Collectors.toMap(uuid -> uuid,
                        uuid -> new AuthorizationSnapshot(uuid, Role.OWNER, 1)));
            }
            return uuids.stream().collect(java.util.stream.Collectors.toMap(uuid -> uuid, repository.authorization));
        };
        try (Harness harness = new Harness(repository, LIMITS, null, refreshLimits())) {
            PlayerAttempt old = attempt();
            await(harness.service.prepare(old));
            await(harness.service.activate(old.token(), JOINED));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            CompletionStage<Void> departure;
            CompletionStage<PreparedPlayer> preparation;
            PlayerAttempt next = new PlayerAttempt(UUID.randomUUID(), old.identity());
            try {
                departure = harness.service.disconnect(old.token(), JOINED.plusSeconds(1));
                repository.authorization = uuid -> new AuthorizationSnapshot(uuid, Role.PLAYER, 2);
                preparation = harness.service.prepare(next);
            } finally {
                release.countDown();
            }
            await(departure);
            assertEquals(2, await(preparation).authorization().revision());
            assertTrue(harness.service.authorization(old.token()).isEmpty());
            assertEquals(Role.PLAYER, harness.service.authorization(next.token()).orElseThrow().role());
            assertTrue(harness.failures.isEmpty());
        }
    }

    @Test
    void roleChangePublishesCommittedAuthorityBeforeReportingSuccess() throws Exception {
        FakeRepository repository = new FakeRepository();
        repository.roleMutation = request -> {
            repository.authorization = uuid -> new AuthorizationSnapshot(uuid, request.role(), 1);
            return new RoleChangeResult(RoleChangeResult.Status.CHANGED,
                    repository.authorization.apply(request.targetUuid()), UUID.randomUUID());
        };
        try (Harness harness = new Harness(repository, LIMITS)) {
            PlayerAttempt player = attempt();
            await(harness.service.prepare(player));
            RoleChangeResult result = await(harness.service.changeRole(new RoleChangeRequest(
                    new AuthorizationActor.LocalConsole(), player.identity().uuid(), Role.ADMIN, "Staff appointment")));
            assertEquals(result.authorization(), harness.service.authorization(player.token()).orElseThrow());
            assertNotNull(result.auditId());
        }
    }

    @Test
    void stoppingDuringRoleWriteRejectsReplyAndDoesNotPublishLateAuthority() throws Exception {
        FakeRepository repository = new FakeRepository();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repository.roleMutation = request -> {
            entered.countDown();
            waitFor(release);
            return new RoleChangeResult(RoleChangeResult.Status.CHANGED,
                    new AuthorizationSnapshot(request.targetUuid(), Role.ADMIN, 1), UUID.randomUUID());
        };
        try (Harness harness = new Harness(repository, LIMITS)) {
            PlayerAttempt player = attempt();
            await(harness.service.prepare(player));
            CompletionStage<RoleChangeResult> change = harness.service.changeRole(new RoleChangeRequest(
                    new AuthorizationActor.LocalConsole(), player.identity().uuid(), Role.ADMIN, "Staff appointment"));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                harness.service.requestStop(JOINED.plusSeconds(10));
                assertFailure(UNAVAILABLE, change);
            } finally {
                release.countDown();
            }
            harness.barrier();
            assertTrue(harness.service.authorization(player.token()).isEmpty());
            assertTrue(harness.clock.getQueue().isEmpty());
        }
    }

    @Test
    void conflictingRoleAtTheSameRevisionFailsClosed() throws Exception {
        FakeRepository repository = new FakeRepository();
        repository.authorizationBatch = uuids -> uuids.stream().collect(java.util.stream.Collectors.toMap(
                uuid -> uuid, uuid -> new AuthorizationSnapshot(uuid, Role.ADMIN, 0)));
        try (Harness harness = new Harness(repository, LIMITS, null, refreshLimits())) {
            PlayerAttempt player = attempt();
            await(harness.service.prepare(player));
            waitUntil(() -> !harness.failures.isEmpty());
            assertFalse(harness.service.isRunning());
            assertTrue(harness.service.authorization(player.token()).isEmpty());
            assertEquals(1, harness.failures.size());
        }
    }

    @Test
    void slowPreparationCannotPublishAnAlreadyExpiredAuthorization() throws Exception {
        FakeRepository repository = new FakeRepository();
        repository.read = uuid -> {
            try {
                Thread.sleep(350);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            return Optional.empty();
        };
        try (Harness harness = new Harness(repository, LIMITS, null, refreshLimits())) {
            PlayerAttempt player = attempt();
            assertFailure(TIMED_OUT, harness.service.prepare(player));
            assertFalse(harness.service.isPrepared(player.token()));
            assertTrue(harness.service.authorization(player.token()).isEmpty());
            assertTrue(harness.failures.isEmpty());
        }
    }

    private static final class Harness implements AutoCloseable {
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final CapturingClock clock = new CapturingClock();
        private final List<String> failures = new CopyOnWriteArrayList<>();
        private final PlayerService service;

        private Harness(FakeRepository repository, PlayerServiceLimits limits) throws Exception {
            this(repository, limits, null);
        }

        private Harness(FakeRepository repository, PlayerServiceLimits limits, Executor rejectingWorker) throws Exception {
            this(repository, limits, rejectingWorker, AuthorizationRefreshLimits.DEFAULT);
        }

        private Harness(FakeRepository repository, PlayerServiceLimits limits, Executor rejectingWorker,
                        AuthorizationRefreshLimits refreshLimits) throws Exception {
            clock.setRemoveOnCancelPolicy(true);
            Logger logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            service = worker.submit(() -> {
                PlayerService initialized = new PlayerService(repository,
                        rejectingWorker == null ? worker : rejectingWorker, clock, failures::add,
                        logger, Clock.fixed(JOINED.plusSeconds(3600), ZoneOffset.UTC), limits, refreshLimits);
                initialized.startOnWorker();
                return initialized;
            }).get(2, TimeUnit.SECONDS);
        }

        private void barrier() throws Exception {
            worker.submit(() -> {
            }).get(2, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            service.requestStop(JOINED.plusSeconds(3600));
            try {
                worker.submit(service::closeOnWorker).get(3, TimeUnit.SECONDS);
            } finally {
                worker.shutdown();
                clock.shutdownNow();
                assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
                assertTrue(clock.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }

    private static final class CapturingClock extends ScheduledThreadPoolExecutor {
        private volatile Runnable lastDeadline;

        private CapturingClock() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            lastDeadline = command;
            return super.schedule(command, delay, unit);
        }
    }

    private static final class FakeRepository implements PlayerSessionRepository {
        private final AtomicInteger recoveries = new AtomicInteger();
        private final AtomicInteger reads = new AtomicInteger();
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final List<SessionStart> joins = new CopyOnWriteArrayList<>();
        private final List<End> ends = new CopyOnWriteArrayList<>();
        private final List<CommandAuditEvent> audits = new CopyOnWriteArrayList<>();
        private volatile boolean failAudit;
        private volatile Function<UUID, Optional<PlayerProfile>> read = uuid -> Optional.empty();
        private volatile Function<UUID, AuthorizationSnapshot> authorization = AuthorizationSnapshot::initial;
        private volatile Function<List<UUID>, Map<UUID, AuthorizationSnapshot>> authorizationBatch;
        private volatile Function<RoleChangeRequest, RoleChangeResult> roleMutation;
        private volatile Consumer<SessionStart> join = start -> {
        };
        private volatile Runnable end = () -> {
        };
        private volatile Runnable ownerCheck = () -> {
        };
        private Thread owner;

        @Override
        public int recoverInterrupted(Instant at) {
            owner = Thread.currentThread();
            events.add("recover");
            recoveries.incrementAndGet();
            return 0;
        }

        @Override
        public LoadedPlayer loadPlayer(UUID uuid) {
            return new LoadedPlayer(findPlayer(uuid), authorization.apply(uuid));
        }

        @Override
        public Map<UUID, AuthorizationSnapshot> loadAuthorizations(List<UUID> uuids) {
            assertOwner();
            if (authorizationBatch != null) {
                return authorizationBatch.apply(uuids);
            }
            return uuids.stream().collect(java.util.stream.Collectors.toMap(uuid -> uuid, authorization));
        }

        @Override
        public RoleChangeResult changeRole(RoleChangeRequest request) {
            assertOwner();
            return roleMutation.apply(request);
        }

        @Override
        public void appendCommandAudit(CommandAuditEvent event) throws PersistenceException {
            assertOwner();
            if (failAudit) {
                throw new PersistenceException(PersistenceException.Kind.DATABASE_FAILURE,
                        "Player persistence command audit failed.");
            }
            audits.add(event);
            events.add("audit");
        }

        @Override
        public Optional<PlayerProfile> findPlayer(UUID uuid) {
            assertOwner();
            reads.incrementAndGet();
            events.add("load");
            return read.apply(uuid);
        }

        @Override
        public Optional<PlayerSession> findSession(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PlayerProfile join(SessionStart start) {
            assertOwner();
            events.add("join");
            joins.add(start);
            join.accept(start);
            return new PlayerProfile(start.playerUuid(), start.username(), start.joinedAt(), start.joinedAt());
        }

        @Override
        public boolean endSession(UUID id, Instant at, SessionEnd reason) {
            assertOwner();
            end.run();
            events.add(reason == SessionEnd.QUIT ? "quit" : "shutdown");
            ends.add(new End(id, at, reason));
            return true;
        }

        @Override
        public void checkOwnership() {
            assertOwner();
            ownerCheck.run();
        }

        @Override
        public void close() {
            assertOwner();
            events.add("close");
        }

        private void assertOwner() {
            assertSame(owner, Thread.currentThread());
        }
    }

    private record End(UUID id, Instant at, SessionEnd reason) {
    }
}
