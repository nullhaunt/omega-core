package com.omegarealm.core.scheduler.paper;

import com.omegarealm.core.scheduler.OmegaScheduler;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class PaperOmegaSchedulerTest {

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void get(Future<?> future) {
        try {
            future.get(2, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void globalUsesQueuedPaperDispatchAndCompletedWorkIsNotRetainedForCancellation() throws Exception {
        try (Harness harness = new Harness()) {
            AtomicInteger calls = new AtomicInteger();
            harness.scheduler.global(calls::incrementAndGet);
            assertEquals(0, calls.get());
            Slot global = harness.slots.getFirst();
            assertFalse(global.repeating);
            harness.threads.submit(global::fire).get(2, TimeUnit.SECONDS);
            global.fire();
            assertEquals(1, calls.get());
            harness.scheduler.close();
            assertEquals(0, global.cancellations.get());
        }
    }

    @Test
    void repeatingAsyncPreservesDurationsAndIndividualCancellationSuppressesLateCallbacks() throws Exception {
        try (Harness harness = new Harness()) {
            AtomicInteger calls = new AtomicInteger();
            Duration initial = Duration.ofMillis(250);
            Duration period = Duration.ofMillis(500);
            OmegaScheduler.Task task = harness.scheduler.repeatAsync(initial, period, calls::incrementAndGet);
            Slot repeat = harness.slots.getFirst();
            assertTrue(repeat.repeating);
            assertEquals(initial.toNanos(), repeat.initial);
            assertEquals(period.toNanos(), repeat.period);
            assertEquals(TimeUnit.NANOSECONDS, repeat.unit);
            assertEquals(0, calls.get());
            repeat.fire();
            repeat.fire();
            task.cancel();
            task.cancel();
            repeat.fire();
            assertEquals(2, calls.get());
            assertEquals(1, repeat.cancellations.get());
            assertTrue(task.isCancelled());
            AtomicInteger remaining = new AtomicInteger();
            harness.repeat(remaining::incrementAndGet);
            harness.slots.getLast().fire();
            assertEquals(1, remaining.get());
        }
    }

    @Test
    void closeCancelsQueuedAndRepeatingWorkOnceAndRejectsNewSubmissions() throws Exception {
        try (Harness harness = new Harness()) {
            AtomicInteger calls = new AtomicInteger();
            harness.scheduler.global(calls::incrementAndGet);
            OmegaScheduler.Task repeat = harness.repeat(calls::incrementAndGet);
            harness.scheduler.close();
            harness.scheduler.close();
            for (Slot slot : harness.slots) {
                assertEquals(1, slot.cancellations.get());
                slot.fire();
            }
            assertEquals(0, calls.get());
            assertTrue(repeat.isCancelled());
            assertThrows(RejectedExecutionException.class, () -> harness.scheduler.global(() -> {
            }));
            assertThrows(RejectedExecutionException.class, () -> harness.repeat(() -> {
            }));
            assertEquals(2, harness.slots.size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void platformSubmissionRejectionIsControlledAndInvalidatesAcceptedCallback(boolean repeating) throws Exception {
        try (Harness harness = new Harness()) {
            AtomicInteger calls = new AtomicInteger();
            harness.onSubmission = ignored -> {
                throw new IllegalStateException("DO_NOT_LOG_THIS_SECRET");
            };
            RejectedExecutionException failure = assertThrows(RejectedExecutionException.class,
                    () -> harness.submit(repeating, calls::incrementAndGet));
            assertNull(failure.getCause());
            assertFalse(failure.getMessage().contains("DO_NOT_LOG"));
            Slot abandoned = harness.slots.getFirst();
            abandoned.fire();
            assertEquals(0, calls.get());
            assertEquals(0, harness.failures.get());
            // A thrown submission never returned its native handle. Paper retains ownership
            // of that handle; the application's abandoned callback remains fenced off.
            harness.onSubmission = ignored -> {
            };
            harness.submit(repeating, calls::incrementAndGet);
            harness.slots.getLast().fire();
            assertEquals(1, calls.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closeWinsOverHandleReturnedAfterSubmission(boolean repeating) throws Exception {
        try (Harness harness = new Harness()) {
            CountDownLatch accepted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            harness.onSubmission = ignored -> {
                accepted.countDown();
                await(release);
            };
            AtomicInteger calls = new AtomicInteger();
            Future<?> submitting = harness.threads.submit(() -> harness.submit(repeating, calls::incrementAndGet));
            try {
                assertTrue(accepted.await(2, TimeUnit.SECONDS));
                harness.scheduler.close();
            } finally {
                release.countDown();
            }
            submitting.get(2, TimeUnit.SECONDS);
            Slot late = harness.slots.getFirst();
            assertEquals(1, late.cancellations.get());
            late.fire();
            assertEquals(0, calls.get());
        }
    }

    @Test
    void oneShotCompletionBeforeHandleReturnDoesNotLeakOrCancelCompletedWork() throws Exception {
        try (Harness harness = new Harness()) {
            AtomicInteger calls = new AtomicInteger();
            harness.onSubmission = slot -> get(harness.threads.submit(slot::fire));
            harness.scheduler.global(calls::incrementAndGet);
            assertEquals(1, calls.get());
            harness.scheduler.close();
            assertEquals(0, harness.slots.getFirst().cancellations.get());
        }
    }

    @Test
    void callbackFailureBeforeHandleReturnCancelsTheLateHandle() throws Exception {
        try (Harness harness = new Harness()) {
            harness.onSubmission = slot -> get(harness.threads.submit(slot::fire));
            OmegaScheduler.Task task = harness.repeat(() -> {
                throw new IllegalStateException("DO_NOT_LOG_THIS_SECRET");
            });
            assertTrue(task.isCancelled());
            assertEquals(1, harness.failures.get());
            assertEquals(1, harness.slots.getFirst().cancellations.get());
            assertTrue(harness.logs.stream().noneMatch(message -> message.contains("DO_NOT_LOG")));
        }
    }

    @Test
    void closeDoesNotWaitForCallbackAlreadyRunningAndSuppressesItsNextInvocation() throws Exception {
        try (Harness harness = new Harness()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger completed = new AtomicInteger();
            harness.repeat(() -> {
                entered.countDown();
                await(release);
                completed.incrementAndGet();
            });
            Slot slot = harness.slots.getFirst();
            Future<?> executing = harness.threads.submit(slot::fire);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                harness.threads.submit(harness.scheduler::close).get(2, TimeUnit.SECONDS);
                assertEquals(0, completed.get());
            } finally {
                release.countDown();
            }
            executing.get(2, TimeUnit.SECONDS);
            slot.fire();
            assertEquals(1, completed.get());
            assertEquals(1, slot.cancellations.get());
        }
    }

    @Test
    void overlappingRepeatIsSkippedAndLaterRepeatStillRuns() throws Exception {
        try (Harness harness = new Harness()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            harness.repeat(() -> {
                calls.incrementAndGet();
                entered.countDown();
                await(release);
            });
            Slot slot = harness.slots.getFirst();
            Future<?> executing = harness.threads.submit(slot::fire);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                slot.fire();
                assertEquals(1, calls.get());
            } finally {
                release.countDown();
            }
            executing.get(2, TimeUnit.SECONDS);
            slot.fire();
            assertEquals(2, calls.get());
        }
    }

    @Test
    void callbackAndNativeCancellationBothRunOutsideOwnershipMonitor() throws Exception {
        try (Harness harness = new Harness()) {
            harness.scheduler.global(() -> get(harness.threads.submit(() -> harness.scheduler.global(() -> {
            }))));
            harness.slots.getFirst().fire();
            OmegaScheduler.Task repeat = harness.repeat(() -> {
            });
            Slot slot = harness.slots.getLast();
            slot.onCancel = () -> get(harness.threads.submit(() -> harness.scheduler.global(() -> {
            })));
            repeat.cancel();
            assertEquals(4, harness.slots.size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callbackExceptionOrLinkageFailureClosesPeerTasksAndSignalsOnce(boolean linkage) throws Exception {
        try (Harness harness = new Harness()) {
            harness.repeat(() -> {
                if (linkage) {
                    throw new LinkageError("DO_NOT_LOG_THIS_SECRET");
                }
                throw new IllegalStateException("DO_NOT_LOG_THIS_SECRET");
            });
            AtomicInteger peerCalls = new AtomicInteger();
            harness.scheduler.global(peerCalls::incrementAndGet);
            harness.slots.getFirst().fire();
            harness.slots.getFirst().fire();
            harness.slots.getLast().fire();
            assertEquals(0, peerCalls.get());
            assertEquals(1, harness.failures.get());
            assertTrue(harness.slots.stream().allMatch(slot -> slot.cancellations.get() == 1));
            assertTrue(harness.logs.stream().noneMatch(message -> message.contains("DO_NOT_LOG")));
            assertThrows(RejectedExecutionException.class, () -> harness.scheduler.global(() -> {
            }));
        }
    }

    @Test
    void cancellationFailureStillFencesAllCallbacksAndReportsControlledFailure() throws Exception {
        try (Harness harness = new Harness()) {
            AtomicInteger calls = new AtomicInteger();
            harness.repeat(calls::incrementAndGet);
            harness.scheduler.global(calls::incrementAndGet);
            harness.slots.getFirst().onCancel = () -> {
                throw new IllegalStateException("DO_NOT_LOG_THIS_SECRET");
            };
            harness.scheduler.close();
            harness.slots.forEach(Slot::fire);
            assertEquals(0, calls.get());
            assertEquals(1, harness.failures.get());
            assertTrue(harness.slots.stream().allMatch(slot -> slot.cancellations.get() == 1));
            assertTrue(harness.logs.stream().noneMatch(message -> message.contains("DO_NOT_LOG")));
        }
    }

    @Test
    void invalidDurationsDoNotSubmitPlatformWork() throws Exception {
        try (Harness harness = new Harness()) {
            assertThrows(IllegalArgumentException.class,
                    () -> harness.scheduler.repeatAsync(Duration.ZERO, Duration.ofSeconds(1), () -> {
                    }));
            assertThrows(IllegalArgumentException.class,
                    () -> harness.scheduler.repeatAsync(Duration.ofSeconds(1), Duration.ofSeconds(-1), () -> {
                    }));
            assertThrows(ArithmeticException.class,
                    () -> harness.scheduler.repeatAsync(Duration.ofSeconds(Long.MAX_VALUE), Duration.ofSeconds(1), () -> {
                    }));
            assertTrue(harness.slots.isEmpty());
        }
    }

    private static final class Harness implements AutoCloseable {
        private final ExecutorService threads = Executors.newCachedThreadPool();
        private final List<Slot> slots = new CopyOnWriteArrayList<>();
        private final List<String> logs = new CopyOnWriteArrayList<>();
        private final AtomicInteger failures = new AtomicInteger();
        private final Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
                    throw new AssertionError("Unexpected plugin access");
                });
        private final PaperOmegaScheduler scheduler;
        private volatile Consumer<Slot> onSubmission = ignored -> {
        };

        private Harness() {
            Logger logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    logs.add(record.getMessage());
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
            GlobalRegionScheduler global = (GlobalRegionScheduler) Proxy.newProxyInstance(
                    GlobalRegionScheduler.class.getClassLoader(), new Class<?>[]{GlobalRegionScheduler.class},
                    (proxy, method, args) -> {
                        assertEquals("run", method.getName());
                        return accept(args, false);
                    });
            AsyncScheduler async = (AsyncScheduler) Proxy.newProxyInstance(AsyncScheduler.class.getClassLoader(),
                    new Class<?>[]{AsyncScheduler.class}, (proxy, method, args) -> {
                        assertEquals("runAtFixedRate", method.getName());
                        return accept(args, true);
                    });
            scheduler = new PaperOmegaScheduler(plugin, global, async, logger, failures::incrementAndGet);
        }

        @SuppressWarnings("unchecked")
        private Slot accept(Object[] arguments, boolean repeating) {
            assertSame(plugin, arguments[0]);
            Slot slot = new Slot(plugin, (Consumer<ScheduledTask>) arguments[1], repeating);
            if (repeating) {
                slot.initial = (long) arguments[2];
                slot.period = (long) arguments[3];
                slot.unit = (TimeUnit) arguments[4];
            }
            slots.add(slot);
            onSubmission.accept(slot);
            return slot;
        }

        private OmegaScheduler.Task repeat(Runnable task) {
            return scheduler.repeatAsync(Duration.ofMillis(250), Duration.ofMillis(250), task);
        }

        private void submit(boolean repeating, Runnable task) {
            if (repeating) {
                repeat(task);
            } else {
                scheduler.global(task);
            }
        }

        @Override
        public void close() throws InterruptedException {
            scheduler.close();
            threads.shutdownNow();
            assertTrue(threads.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static final class Slot implements ScheduledTask {
        private final Plugin plugin;
        private final Consumer<ScheduledTask> callback;
        private final boolean repeating;
        private final AtomicInteger cancellations = new AtomicInteger();
        private volatile Runnable onCancel = () -> {
        };
        private long initial;
        private long period;
        private TimeUnit unit;

        private Slot(Plugin plugin, Consumer<ScheduledTask> callback, boolean repeating) {
            this.plugin = plugin;
            this.callback = callback;
            this.repeating = repeating;
        }

        private void fire() {
            callback.accept(this);
        }

        @Override
        public Plugin getOwningPlugin() {
            return plugin;
        }

        @Override
        public boolean isRepeatingTask() {
            return repeating;
        }

        @Override
        public CancelledState cancel() {
            cancellations.incrementAndGet();
            onCancel.run();
            return CancelledState.CANCELLED_BY_CALLER;
        }

        @Override
        public ExecutionState getExecutionState() {
            return cancellations.get() == 0 ? ExecutionState.IDLE : ExecutionState.CANCELLED;
        }
    }
}
