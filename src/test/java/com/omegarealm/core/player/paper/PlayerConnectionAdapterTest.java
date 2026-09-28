package com.omegarealm.core.player.paper;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import com.omegarealm.core.RuntimeState;
import com.omegarealm.core.authorization.AuthorizationRefreshLimits;
import com.omegarealm.core.authorization.AuthorizationSnapshot;
import com.omegarealm.core.player.*;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerConnection;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class PlayerConnectionAdapterTest {

    private static <T> T proxy(Class<T> type, java.util.function.Function<String, Object> values) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> values.apply(method.getName())));
    }

    @Test
    void configurationWaitsForProfileBeforeValidationAndJoinDoesNotReadAgain() throws Exception {
        try (Harness harness = new Harness()) {
            Connection first = new Connection(1000, UUID.randomUUID());
            harness.repository.blockRead = true;
            Future<?> configuring = harness.configureAsync(first);
            assertTrue(harness.repository.entered.await(2, TimeUnit.SECONDS));
            assertFalse(configuring.isDone());
            assertEquals(0, harness.repository.joins.size());
            harness.repository.release.countDown();
            configuring.get(2, TimeUnit.SECONDS);
            assertTrue(harness.validate(first, null).isAllowed());
            harness.adapter.onJoin(new PlayerJoinEvent(first.player, Component.empty()));
            harness.barrier();
            assertEquals(first.uuid, harness.adapter.authorization(first.player).orElseThrow().playerUuid());
            Connection replacement = new Connection(1000, first.uuid);
            assertTrue(harness.adapter.authorization(replacement.player).isEmpty());
            assertEquals(1, harness.repository.reads.get());
            assertEquals(first.uuid, harness.repository.joins.getFirst().playerUuid());
            harness.adapter.onQuit(new PlayerQuitEvent(first.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
            harness.barrier();
            assertTrue(harness.adapter.authorization(first.player).isEmpty());
            assertEquals(List.of(SessionEnd.QUIT), harness.repository.ends);
            assertEquals(0, harness.failures.get());
        }
    }

    @Test
    void repeatedConfigurationWaitsForTheSamePreparation() throws Exception {
        try (Harness harness = new Harness()) {
            Connection first = new Connection(1000, UUID.randomUUID());
            harness.repository.blockRead = true;
            Future<?> original = harness.configureAsync(first);
            assertTrue(harness.repository.entered.await(2, TimeUnit.SECONDS));
            Future<?> repeated = harness.configureAsync(first);
            assertFalse(original.isDone());
            harness.repository.release.countDown();
            original.get(2, TimeUnit.SECONDS);
            repeated.get(2, TimeUnit.SECONDS);
            assertTrue(harness.validate(first, null).isAllowed());
            assertEquals(1, harness.repository.reads.get());
        }
    }

    @Test
    void disconnectedLoadFinishesHandlerWithoutPublishingAndReconnectUsesNewToken() throws Exception {
        try (Harness harness = new Harness()) {
            Connection old = new Connection(1000, UUID.randomUUID());
            harness.repository.blockRead = true;
            Future<?> configuring = harness.configureAsync(old);
            assertTrue(harness.repository.entered.await(2, TimeUnit.SECONDS));
            old.connected.set(false);
            configuring.get(2, TimeUnit.SECONDS);
            assertFalse(harness.validate(old, null).isAllowed());
            harness.repository.blockRead = false;
            harness.repository.release.countDown();
            Connection reconnect = new Connection(1001, old.uuid);
            harness.configure(reconnect);
            assertTrue(harness.validate(reconnect, null).isAllowed());
            harness.adapter.onJoin(new PlayerJoinEvent(reconnect.player, Component.empty()));
            harness.barrier();
            assertEquals(1, harness.repository.joins.size());
            assertEquals(0, harness.failures.get());
        }
    }

    @Test
    void initialLoginDoesNotRequirePreparationButUnknownConnectionPhaseFailsClosed() throws Exception {
        try (Harness harness = new Harness()) {
            PlayerLoginConnection login = proxy(PlayerLoginConnection.class, method -> {
                throw new AssertionError("Initial validation must not touch connection state");
            });
            var initial = new PlayerConnectionValidateLoginEvent(login, null);
            harness.adapter.onValidate(initial);
            assertTrue(initial.isAllowed());
            PlayerConnection unknown = proxy(PlayerConnection.class, method -> {
                throw new AssertionError("Unknown connection must not send packets");
            });
            var unsupported = new PlayerConnectionValidateLoginEvent(unknown, null);
            harness.adapter.onValidate(unsupported);
            assertFalse(unsupported.isAllowed());
            assertEquals(1, harness.failures.get());
        }
    }

    @Test
    void missingPreparationAndDifferentConfigurationHandleAreRejected() throws Exception {
        try (Harness harness = new Harness()) {
            Connection original = new Connection(1000, UUID.randomUUID());
            assertFalse(harness.validate(original, null).isAllowed());
            harness.configure(original);
            Connection collision = new Connection(1000, original.uuid);
            assertFalse(harness.validate(collision, null).isAllowed());
            assertTrue(harness.validate(original, null).isAllowed());
            assertEquals(0, harness.repository.joins.size());
        }
    }

    @Test
    void otherValidationRejectionIsPreservedAndCancelsPreparedAttempt() throws Exception {
        try (Harness harness = new Harness()) {
            Connection first = new Connection(1000, UUID.randomUUID());
            harness.configure(first);
            Component ban = Component.text("Existing ban");
            assertSame(ban, harness.validate(first, ban).getKickMessage());
            Connection retry = new Connection(1001, first.uuid);
            harness.configure(retry);
            assertTrue(harness.validate(retry, null).isAllowed());
            assertEquals(0, harness.repository.joins.size());
        }
    }

    @Test
    void duplicateIdentityCannotReplaceOriginalPreparedConnection() throws Exception {
        try (Harness harness = new Harness()) {
            Connection first = new Connection(1000, UUID.randomUUID());
            harness.configure(first);
            Connection duplicate = new Connection(1001, first.uuid);
            harness.configure(duplicate);
            assertFalse(harness.validate(duplicate, null).isAllowed());
            assertTrue(harness.validate(first, null).isAllowed());
            assertEquals(1, harness.repository.reads.get());
            assertEquals(0, harness.failures.get());
        }
    }

    @Test
    void reconnectAtReusedEndpointDoesNotNeedToWaitForPeriodicSweep() throws Exception {
        try (Harness harness = new Harness()) {
            Connection old = new Connection(1000, UUID.randomUUID());
            harness.configure(old);
            old.connected.set(false);
            Connection reconnect = new Connection(1000, old.uuid);
            harness.configure(reconnect);
            assertTrue(harness.validate(reconnect, null).isAllowed());
            assertEquals(0, harness.failures.get());
        }
    }

    @Test
    void uuidOnlyCloseCallbackCannotRemoveConnectedReconnect() throws Exception {
        try (Harness harness = new Harness()) {
            Connection old = new Connection(1000, UUID.randomUUID());
            harness.configure(old);
            old.connected.set(false);
            harness.adapter.reconcile();
            Connection reconnect = new Connection(1001, old.uuid);
            harness.configure(reconnect);
            harness.adapter.onConnectionClose(new PlayerConnectionCloseEvent(old.uuid, "Player",
                    InetAddress.getLoopbackAddress(), true));
            assertTrue(harness.validate(reconnect, null).isAllowed());
            assertEquals(0, harness.failures.get());
        }
    }

    @Test
    void quitWhileJoinIsPendingDoesNotWaitOrTreatCancellationAsCriticalFailure() throws Exception {
        try (Harness harness = new Harness()) {
            Connection first = new Connection(1000, UUID.randomUUID());
            harness.configure(first);
            harness.repository.blockJoin = true;
            harness.adapter.onJoin(new PlayerJoinEvent(first.player, Component.empty()));
            assertTrue(harness.repository.entered.await(2, TimeUnit.SECONDS));
            harness.adapter.onQuit(new PlayerQuitEvent(first.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
            Connection reconnect = new Connection(1001, first.uuid);
            Future<?> configuring = harness.configureAsync(reconnect);
            harness.repository.release.countDown();
            configuring.get(2, TimeUnit.SECONDS);
            harness.barrier();
            assertEquals(List.of(SessionEnd.QUIT), harness.repository.ends);
            assertTrue(harness.validate(reconnect, null).isAllowed());
            assertEquals(0, harness.failures.get());
        }
    }

    @Test
    void runtimeStopDuringLoadingRejectsValidationAndCannotRecordJoin() throws Exception {
        try (Harness harness = new Harness()) {
            Connection first = new Connection(1000, UUID.randomUUID());
            harness.repository.blockRead = true;
            Future<?> configuring = harness.configureAsync(first);
            assertTrue(harness.repository.entered.await(2, TimeUnit.SECONDS));
            harness.state.set(RuntimeState.STOPPING);
            harness.adapter.close();
            harness.service.requestStop(Instant.now());
            configuring.get(2, TimeUnit.SECONDS);
            assertFalse(harness.validate(first, null).isAllowed());
            harness.repository.release.countDown();
            harness.barrier();
            assertEquals(0, harness.repository.joins.size());
        }
    }

    @Test
    void quitDuringServerStopLeavesShutdownClosureToRuntime() throws Exception {
        Harness harness = new Harness();
        try {
            Connection first = new Connection(1000, UUID.randomUUID());
            harness.configure(first);
            harness.adapter.onJoin(new PlayerJoinEvent(first.player, Component.empty()));
            harness.barrier();
            harness.state.set(RuntimeState.STOPPING);
            harness.adapter.onQuit(new PlayerQuitEvent(first.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
            harness.barrier();
            assertTrue(harness.repository.ends.isEmpty());
        } finally {
            harness.close();
        }
        assertEquals(List.of(SessionEnd.SHUTDOWN), harness.repository.ends);
    }

    @Test
    void unexpectedWorldJoinWithoutPreparedAttemptIsCritical() throws Exception {
        try (Harness harness = new Harness()) {
            Connection first = new Connection(1000, UUID.randomUUID());
            harness.adapter.onJoin(new PlayerJoinEvent(first.player, Component.empty()));
            assertEquals(1, harness.failures.get());
            assertEquals(0, harness.repository.joins.size());
        }
    }

    @Test
    void freshnessExpirySchedulesOneKickOnTheOwningEntityContext() throws Exception {
        try (Harness harness = new Harness(new AuthorizationRefreshLimits(
                Duration.ofMillis(40), Duration.ofMillis(300), 2))) {
            Connection connection = new Connection(1000, UUID.randomUUID());
            harness.configure(connection);
            harness.adapter.onJoin(new PlayerJoinEvent(connection.player, Component.empty()));
            harness.barrier();
            harness.repository.blockRefresh = true;
            try {
                assertTrue(harness.repository.entered.await(2, TimeUnit.SECONDS));
                Thread.sleep(350);
                harness.adapter.reconcile();
                harness.adapter.reconcile();
                assertEquals(1, connection.entityTasks.size());
                assertEquals(0, connection.kicks.get());
                connection.entityTasks.remove().run();
                assertEquals(1, connection.kicks.get());
                assertEquals(0, harness.failures.get());
            } finally {
                harness.repository.release.countDown();
            }
        }
    }

    @Test
    void delayedExpiryKickCannotAffectAReconnectedPlayer() throws Exception {
        try (Harness harness = new Harness(new AuthorizationRefreshLimits(
                Duration.ofMillis(40), Duration.ofMillis(300), 2))) {
            Connection old = new Connection(1000, UUID.randomUUID());
            harness.configure(old);
            harness.adapter.onJoin(new PlayerJoinEvent(old.player, Component.empty()));
            harness.barrier();
            harness.repository.blockRefresh = true;
            try {
                assertTrue(harness.repository.entered.await(2, TimeUnit.SECONDS));
                Thread.sleep(350);
                harness.adapter.reconcile();
                assertEquals(1, old.entityTasks.size());
                harness.adapter.onQuit(new PlayerQuitEvent(old.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
            } finally {
                harness.repository.blockRefresh = false;
                harness.repository.release.countDown();
            }
            harness.barrier();
            Connection next = new Connection(1001, old.uuid);
            harness.configure(next);
            assertTrue(harness.validate(next, null).isAllowed());
            harness.adapter.onJoin(new PlayerJoinEvent(next.player, Component.empty()));
            old.entityTasks.remove().run();
            assertEquals(0, old.kicks.get());
            assertEquals(0, next.kicks.get());
            assertEquals(0, harness.failures.get());
        }
    }

    private static final class Harness implements AutoCloseable {
        private final ExecutorService io = Executors.newSingleThreadExecutor();
        private final ExecutorService events = Executors.newCachedThreadPool();
        private final ScheduledThreadPoolExecutor clock = new ScheduledThreadPoolExecutor(1);
        private final AtomicReference<RuntimeState> state = new AtomicReference<>(RuntimeState.READY);
        private final AtomicInteger failures = new AtomicInteger();
        private final Repository repository = new Repository();
        private final PlayerService service;
        private final PlayerConnectionAdapter adapter;

        private Harness() throws Exception {
            this(AuthorizationRefreshLimits.DEFAULT);
        }

        private Harness(AuthorizationRefreshLimits refreshLimits) throws Exception {
            Logger logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            clock.setRemoveOnCancelPolicy(true);
            service = io.submit(() -> {
                PlayerService initialized = new PlayerService(repository, io, clock,
                        ignored -> failures.incrementAndGet(), logger, Clock.systemUTC(), PlayerServiceLimits.DEFAULT, refreshLimits);
                initialized.startOnWorker();
                return initialized;
            }).get(2, TimeUnit.SECONDS);
            adapter = new PlayerConnectionAdapter(proxy(org.bukkit.plugin.Plugin.class, method -> null),
                    state::get, () -> service, failures::incrementAndGet,
                    logger, Clock.systemUTC());
        }

        private Future<?> configureAsync(Connection connection) {
            return events.submit(() -> adapter.onConfigure(new AsyncPlayerConnectionConfigureEvent(connection.configuration)));
        }

        private void configure(Connection connection) throws Exception {
            configureAsync(connection).get(2, TimeUnit.SECONDS);
        }

        private PlayerConnectionValidateLoginEvent validate(Connection connection, Component rejection) {
            PlayerConnectionValidateLoginEvent event = new PlayerConnectionValidateLoginEvent(connection.configuration, rejection);
            adapter.onValidate(event);
            return event;
        }

        private void barrier() throws Exception {
            io.submit(() -> {
            }).get(2, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            adapter.close();
            repository.release.countDown();
            service.requestStop(Instant.now());
            try {
                io.submit(service::closeOnWorker).get(3, TimeUnit.SECONDS);
            } finally {
                events.shutdownNow();
                io.shutdown();
                clock.shutdownNow();
                assertTrue(events.awaitTermination(3, TimeUnit.SECONDS));
                assertTrue(io.awaitTermination(3, TimeUnit.SECONDS));
                assertTrue(clock.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }

    private static final class Connection {
        private final UUID uuid;
        private final AtomicBoolean connected = new AtomicBoolean(true);
        private final PlayerConfigurationConnection configuration;
        private final Player player;
        private final java.util.Queue<Runnable> entityTasks = new ConcurrentLinkedQueue<>();
        private final AtomicInteger kicks = new AtomicInteger();

        private Connection(int port, UUID uuid) {
            this.uuid = uuid;
            InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
            var profile = proxy(com.destroystokyo.paper.profile.PlayerProfile.class, (method) -> switch (method) {
                case "getId" -> uuid;
                case "getName" -> "Player";
                default -> throw new AssertionError("Unexpected profile access: " + method);
            });
            configuration = proxy(PlayerConfigurationConnection.class, method -> switch (method) {
                case "getProfile" -> profile;
                case "getAddress" -> address;
                case "isConnected" -> connected.get();
                default -> throw new AssertionError("Unexpected configuration access: " + method);
            });
            PlayerGameConnection game = proxy(PlayerGameConnection.class, method -> switch (method) {
                case "getAddress" -> address;
                default -> throw new AssertionError("Unexpected game access: " + method);
            });
            var entityScheduler = (io.papermc.paper.threadedregions.scheduler.EntityScheduler) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{io.papermc.paper.threadedregions.scheduler.EntityScheduler.class},
                    (object, method, arguments) -> {
                        if (method.getName().equals("execute")) {
                            entityTasks.add((Runnable) arguments[1]);
                            return true;
                        }
                        throw new AssertionError("Unexpected entity scheduling");
                    });
            player = proxy(Player.class, method -> switch (method) {
                case "getUniqueId" -> uuid;
                case "getName" -> "Player";
                case "getConnection" -> game;
                case "getScheduler" -> entityScheduler;
                case "kick" -> {
                    kicks.incrementAndGet();
                    yield null;
                }
                default -> throw new AssertionError("Unexpected entity access: " + method);
            });
        }
    }

    private static final class Repository implements PlayerSessionRepository {

        private final AtomicInteger reads = new AtomicInteger();
        private final List<SessionStart> joins = new CopyOnWriteArrayList<>();
        private final List<SessionEnd> ends = new CopyOnWriteArrayList<>();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean blockRead;
        private volatile boolean blockJoin;
        private volatile boolean blockRefresh;

        @Override
        public PlayerTargetResolution resolveTarget(PlayerTarget target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void appendCommandAudit(com.omegarealm.core.command.CommandAuditEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LoadedPlayer loadPlayer(UUID uuid) {
            return new LoadedPlayer(findPlayer(uuid), AuthorizationSnapshot.initial(uuid));
        }

        @Override
        public java.util.Map<UUID, AuthorizationSnapshot> loadAuthorizations(List<UUID> uuids) {
            if (blockRefresh) {
                block();
            }
            return uuids.stream().collect(java.util.stream.Collectors.toMap(uuid -> uuid, AuthorizationSnapshot::initial));
        }

        @Override
        public com.omegarealm.core.authorization.RoleChangeResult changeRole(
                com.omegarealm.core.authorization.RoleChangeRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<com.omegarealm.core.player.PlayerProfile> findPlayer(UUID uuid) {
            reads.incrementAndGet();
            if (blockRead) {
                block();
            }
            return Optional.empty();
        }

        @Override
        public Optional<PlayerSession> findSession(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.omegarealm.core.player.PlayerProfile join(SessionStart start) {
            if (blockJoin) {
                block();
            }
            joins.add(start);
            return new com.omegarealm.core.player.PlayerProfile(start.playerUuid(), start.username(), start.joinedAt(), start.joinedAt());
        }

        private void block() {
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }

        @Override
        public boolean endSession(UUID id, Instant at, SessionEnd reason) {
            ends.add(reason);
            return true;
        }

        @Override
        public int recoverInterrupted(Instant at) {
            return 0;
        }

        @Override
        public void checkOwnership() {
        }

        @Override
        public void close() {
        }
    }
}
