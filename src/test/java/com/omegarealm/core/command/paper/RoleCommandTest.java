package com.omegarealm.core.command.paper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.omegarealm.core.RuntimeState;
import com.omegarealm.core.authorization.*;
import com.omegarealm.core.player.*;
import com.omegarealm.core.scheduler.OmegaScheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class RoleCommandTest {

    private static <T> T proxy(Class<T> type, java.util.function.Function<String, Object> values) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, arguments) -> values.apply(method.getName())));
    }

    private static CommandSourceStack source(CommandSender sender, Player executor) {
        return proxy(CommandSourceStack.class, method -> switch (method) {
            case "getSender" -> sender;
            case "getExecutor" -> executor;
            default -> throw new AssertionError("Unexpected source access: " + method);
        });
    }

    private static Player namedPlayer(String name) {
        return proxy(Player.class, method -> {
            if (method.equals("getName")) {
                return name;
            }
            throw new AssertionError(method);
        });
    }

    @Test
    void rejectsPlayersRconAndDelegatedConsoleExecutionThroughTheParser() throws Exception {
        try (Harness harness = new Harness()) {
            CommandSender player = proxy(CommandSender.class, method -> {
                throw new AssertionError("OP/Bukkit permission checks must not establish authority");
            });
            RemoteConsoleCommandSender rcon = proxy(RemoteConsoleCommandSender.class, method -> null);
            Player executor = proxy(Player.class, method -> null);
            for (CommandSourceStack denied : List.of(source(player, null), source(rcon, null), source(harness.console, executor))) {
                assertThrows(CommandSyntaxException.class, () -> harness.dispatcher.execute(harness.command, denied));
                assertFalse(harness.dispatcher.getRoot().getChild("role").canUse(denied));
            }
            assertEquals(0, harness.repository.calls);
        }
    }

    @Test
    void incompleteCommandsExplainUsageWithoutAcceptingWork() throws Exception {
        try (Harness harness = new Harness()) {
            CommandSourceStack source = source(harness.console, null);
            for (String command : List.of("role", "role set",
                    "role set " + harness.target, "role set " + harness.target + " ADMIN")) {
                harness.messages.clear();
                assertEquals(0, harness.dispatcher.execute(command, source));
                assertEquals(List.of("Ω | Change a player's role\n  Command: role set <player> <role> <reason>"),
                        harness.messages);
            }
            assertEquals(0, harness.repository.calls);
        }
    }

    @Test
    void invalidArgumentsExplainWhichInputNeedsCorrection() throws Exception {
        try (Harness harness = new Harness()) {
            CommandSourceStack source = source(harness.console, null);
            assertEquals(0, harness.dispatcher.execute("role set 1-1-1-1-1 ADMIN Staff appointment", source));
            assertEquals(List.of("Ω | Enter a current username or full player ID (UUID)."), harness.messages);
            harness.messages.clear();
            assertEquals(0, harness.dispatcher.execute("role set " + harness.target + " UNKNOWN Staff appointment", source));
            assertEquals(List.of("Ω | Choose a valid role."),
                    harness.messages);
            harness.messages.clear();
            for (String reason : List.of("x".repeat(513), "Staff\nappointment")) {
                assertEquals(0, harness.dispatcher.execute("role set " + harness.target + " ADMIN " + reason, source));
                assertEquals(List.of("Ω | Enter a one-line reason between 1 and 512 characters."),
                        harness.messages);
                harness.messages.clear();
            }
            assertEquals(0, harness.repository.calls);
        }
    }

    @Test
    void selfChangesExplainConsoleRequirementWithoutSubmittingWork() throws Exception {
        try (Harness harness = new Harness()) {
            Player player = harness.player();
            UUID uuid = UUID.randomUUID();
            CommandSourceStack source = source(player, player);
            for (Role actorRole : List.of(Role.ADMIN, Role.OWNER)) {
                harness.authorities.put(player, new AuthorizationSnapshot(uuid, actorRole, 1));
                for (Role requested : Role.values()) {
                    harness.messages.clear();
                    assertEquals(0, harness.dispatcher.execute("role set " + uuid + " " + requested + " Self change", source));
                    assertEquals(List.of("Ω | Use the server console to change your own role."), harness.messages);
                }
            }
            assertEquals(0, harness.repository.calls);
        }
    }

    @Test
    void currentUsernameResolvesToUuidBeforeTheAuditedRoleWrite() throws Exception {
        try (Harness harness = new Harness()) {
            String command = "role set targetplayer MODERATOR Staff appointment";

            assertEquals(1, harness.dispatcher.execute(command, source(harness.console, null)));
            assertTrue(harness.repository.entered.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("Ω | Looking up the player..."), harness.messages);
            assertEquals(harness.target, harness.repository.request.targetUuid());
            assertEquals(Role.MODERATOR, harness.repository.request.role());

            harness.repository.release.countDown();
            harness.worker.submit(() -> {
            }).get(2, TimeUnit.SECONDS);
            harness.replies.remove().run();

            assertEquals("Ω | Role updated\n  TargetPlayer now has the Moderator role.",
                    harness.messages.get(1));
            assertTrue(harness.auditLogs.stream().anyMatch(log -> log.contains("outcome=CHANGED")
                    && log.contains("operation=" + harness.repository.request.operationId())));
        }
    }

    @Test
    void missingOrAmbiguousUsernameNeverSubmitsARoleWrite() throws Exception {
        try (Harness harness = new Harness()) {
            assertEquals(1, harness.dispatcher.execute(
                    "role set UnknownPlayer ADMIN Staff appointment", source(harness.console, null)));
            harness.worker.submit(() -> {
            }).get(2, TimeUnit.SECONDS);
            harness.replies.remove().run();
            assertEquals("Ω | Role not changed\n  No saved player has that current name.",
                    harness.messages.get(1));

            harness.repository.ambiguousTarget = true;
            assertEquals(1, harness.dispatcher.execute(
                    "role set TargetPlayer ADMIN Staff appointment", source(harness.console, null)));
            harness.worker.submit(() -> {
            }).get(2, TimeUnit.SECONDS);
            harness.replies.remove().run();
            assertEquals("Ω | Role not changed\n  More than one player has that name."
                            + " Use a player ID (UUID) to choose one.",
                    harness.messages.get(3));
            assertEquals(0, harness.repository.calls);
        }
    }

    @Test
    void selfChangeByUsernameIsDeniedAfterResolution() throws Exception {
        try (Harness harness = new Harness()) {
            Player player = harness.player();
            harness.authorities.put(player, new AuthorizationSnapshot(harness.target, Role.OWNER, 1));

            assertEquals(1, harness.dispatcher.execute(
                    "role set TargetPlayer ADMIN Self change", source(player, player)));
            harness.worker.submit(() -> {
            }).get(2, TimeUnit.SECONDS);
            harness.playerReplies.remove().run();

            assertEquals(List.of("Ω | Looking up the player...",
                    "Ω | Role not changed\n  Use the server console to change your own role."), harness.messages);
            assertEquals(0, harness.repository.calls);
        }
    }

    @Test
    void staffUsageOmitsCompletionChoicesAndValidationExplainsTheRestriction() throws Exception {
        try (Harness harness = new Harness()) {
            Player player = harness.player();
            CommandSourceStack source = source(player, player);
            harness.authorities.put(player, new AuthorizationSnapshot(UUID.randomUUID(), Role.ADMIN, 1));
            assertEquals(0, harness.dispatcher.execute("role set", source));
            assertEquals(List.of("Ω | Change a player's role\n  Command: /role set <player> <role> <reason>"),
                    harness.messages);
            harness.messages.clear();
            assertEquals(0, harness.dispatcher.execute("role set " + harness.target + " UNKNOWN Staff appointment", source));
            assertEquals(List.of("Ω | Choose a valid role."), harness.messages);
            harness.messages.clear();
            assertEquals(0, harness.dispatcher.execute(harness.command, source));
            assertEquals(List.of("Ω | You can only assign roles below your own."), harness.messages);
            harness.messages.clear();
            assertEquals(0, harness.dispatcher.execute(
                    "role set " + harness.target + " OWNER Staff appointment", source));
            assertEquals(List.of("Ω | Only the server console can assign Owner."), harness.messages);
            assertEquals(0, harness.repository.calls);
        }
    }

    @Test
    void storedPolicyDenialRepliesClearlyOnPlayerContextAfterCompletion() throws Exception {
        try (Harness harness = new Harness()) {
            Player player = harness.player();
            harness.authorities.put(player, new AuthorizationSnapshot(UUID.randomUUID(), Role.OWNER, 1));
            harness.repository.resultStatus = RoleChangeResult.Status.DENIED;
            assertEquals(1, harness.dispatcher.execute(harness.command, source(player, player)));
            assertTrue(harness.repository.entered.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("Ω | Looking up the player..."), harness.messages);
            assertTrue(harness.playerReplies.isEmpty());
            harness.repository.release.countDown();
            harness.worker.submit(() -> {
            }).get(2, TimeUnit.SECONDS);
            assertTrue(harness.replies.isEmpty());
            assertEquals(List.of("Ω | Looking up the player..."), harness.messages);
            harness.playerReplies.remove().run();
            assertEquals(List.of("Ω | Looking up the player...",
                            "Ω | Role not changed\n  You can't manage TargetPlayer or assign that role."),
                    harness.messages);
            assertTrue(harness.playerComponents.getLast().children().stream()
                    .anyMatch(child -> child instanceof TextComponent heading
                            && heading.content().equals("Role not changed")
                            && NamedTextColor.RED.equals(heading.color())
                            && heading.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE));
        }
    }

    @Test
    void unchangedAndMissingWritesExplainTheOutcomeWithoutInternalIdentifiers() throws Exception {
        for (RoleChangeResult.Status status : List.of(
                RoleChangeResult.Status.UNCHANGED, RoleChangeResult.Status.NOT_FOUND)) {
            try (Harness harness = new Harness()) {
                harness.repository.resultStatus = status;

                assertEquals(1, harness.dispatcher.execute(harness.command, source(harness.console, null)));
                assertTrue(harness.repository.entered.await(2, TimeUnit.SECONDS));
                harness.repository.release.countDown();
                harness.worker.submit(() -> {
                }).get(2, TimeUnit.SECONDS);
                harness.replies.remove().run();

                String expected = status == RoleChangeResult.Status.UNCHANGED
                        ? "Ω | No change needed\n  TargetPlayer already has the Admin role."
                        : "Ω | Role not changed\n  We couldn't find TargetPlayer when saving the change.";
                assertEquals(expected, harness.messages.getLast());
                assertFalse(harness.messages.getLast().contains(harness.target.toString()));
            }
        }
    }

    @Test
    void acceptsWithoutWaitingAndRepliesOnSchedulerOnlyAfterCommit() throws Exception {
        try (Harness harness = new Harness()) {
            assertEquals(1, harness.dispatcher.execute(harness.command, source(harness.console, null)));
            assertTrue(harness.repository.entered.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("Ω | Looking up the player..."), harness.messages);
            assertTrue(harness.replies.isEmpty());
            harness.repository.release.countDown();
            harness.worker.submit(() -> {
            }).get(2, TimeUnit.SECONDS);
            assertEquals(List.of("Ω | Looking up the player..."), harness.messages);
            assertEquals(1, harness.replies.size());
            harness.replies.remove().run();
            assertEquals("Ω | Role updated\n  TargetPlayer now has the Admin role.",
                    harness.messages.get(1));
            assertFalse(harness.messages.get(1).contains("Staff appointment"));
            assertFalse(harness.messages.get(1).contains(harness.target.toString()));
            assertEquals("Staff appointment", harness.repository.request.reason());
            assertInstanceOf(AuthorizationActor.LocalConsole.class, harness.repository.request.actor());
            assertTrue(harness.auditLogs.stream().anyMatch(log -> log.contains("category=MANAGEMENT")
                    && log.contains("command=role.set") && log.contains("outcome=CHANGED")
                    && log.contains("mutation_audit=")
                    && log.contains("operation=" + harness.repository.request.operationId())));
            assertTrue(harness.auditLogs.stream().anyMatch(log -> log.contains("outcome=QUEUED")
                    && log.contains("operation=" + harness.repository.request.operationId())));
            assertTrue(harness.auditLogs.stream().noneMatch(log -> log.contains("Staff appointment")));
        }
    }

    @Test
    void staffCommandsUseExactFreshAuthorityAndFilterRoleSuggestions() throws Exception {
        try (Harness harness = new Harness()) {
            Player player = harness.player();
            UUID uuid = UUID.randomUUID();
            var source = source(player, player);
            harness.authorities.put(player, new AuthorizationSnapshot(uuid, Role.ADMIN, 3));
            var suggestions = harness.dispatcher.getCompletionSuggestions(
                    harness.dispatcher.parse("role set " + harness.target + " ", source)).get();
            assertEquals(Set.of("PLAYER", "MODERATOR"), suggestions.getList().stream()
                    .map(com.mojang.brigadier.suggestion.Suggestion::getText).collect(java.util.stream.Collectors.toSet()));
            assertEquals(0, harness.dispatcher.execute(harness.command, source));
            harness.authorities.put(player, new AuthorizationSnapshot(uuid, Role.OWNER, 4));
            assertEquals(1, harness.dispatcher.execute(harness.command, source));
            assertTrue(harness.repository.entered.await(2, TimeUnit.SECONDS));
            assertEquals(new AuthorizationActor.Player(uuid, Role.OWNER), harness.repository.request.actor());
            harness.repository.release.countDown();
            harness.worker.submit(() -> {
            }).get(2, TimeUnit.SECONDS);
            assertTrue(harness.replies.isEmpty());
            assertEquals(1, harness.playerReplies.size());
        }
    }

    @Test
    void targetSuggestionsOfferConnectedNamesOnlyWithCurrentCommandAccess() throws Exception {
        try (Harness harness = new Harness()) {
            harness.onlinePlayers.add(namedPlayer("TargetPlayer"));
            harness.onlinePlayers.add(namedPlayer(".Unreference"));

            var console = source(harness.console, null);
            var names = harness.dispatcher.getCompletionSuggestions(
                    harness.dispatcher.parse("role set tAr", console)).get();
            assertEquals(List.of("TargetPlayer"), names.getList().stream()
                    .map(com.mojang.brigadier.suggestion.Suggestion::getText).toList());

            Player player = harness.player();
            var playerSource = source(player, player);
            harness.authorities.put(player, new AuthorizationSnapshot(UUID.randomUUID(), Role.ADMIN, 1));
            var parsed = harness.dispatcher.parse("role set .u", playerSource);
            var bedrock = harness.dispatcher.getCompletionSuggestions(parsed).get();
            assertEquals(List.of(".Unreference"), bedrock.getList().stream()
                    .map(com.mojang.brigadier.suggestion.Suggestion::getText).toList());

            harness.authorities.remove(player);
            assertTrue(harness.dispatcher.getCompletionSuggestions(parsed).get().isEmpty());
            assertEquals(0, harness.repository.calls);
        }
    }

    @Test
    void revocationBetweenParseAndExecutePreventsSubmittingRoleWork() throws Exception {
        try (Harness harness = new Harness()) {
            Player player = proxy(Player.class, method -> null);
            for (String command : List.of("role set", harness.command)) {
                harness.authorities.put(player, new AuthorizationSnapshot(UUID.randomUUID(), Role.OWNER, 1));
                var parsed = harness.dispatcher.parse(command, source(player, player));
                harness.authorities.remove(player);
                var denied = assertThrows(CommandSyntaxException.class, () -> harness.dispatcher.execute(parsed));
                assertEquals(command, denied.getInput());
                assertEquals(0, denied.getCursor());
            }
            assertTrue(harness.messages.isEmpty());
            assertEquals(0, harness.repository.calls);
        }
    }

    @Test
    void nonStaffCannotAccessUsageOrValidationFeedback() throws Exception {
        try (Harness harness = new Harness()) {
            Player player = harness.player();
            var source = source(player, player);
            for (Role role : List.of(Role.PLAYER, Role.MODERATOR)) {
                harness.authorities.put(player, new AuthorizationSnapshot(UUID.randomUUID(), role, 1));
                for (String command : List.of("role", "role set", harness.command)) {
                    var denied = assertThrows(CommandSyntaxException.class, () -> harness.dispatcher.execute(command, source));
                    assertEquals(command, denied.getInput());
                }
                assertTrue(harness.dispatcher.getCompletionSuggestions(harness.dispatcher.parse("rol", source)).get().isEmpty());
            }
            assertTrue(harness.messages.isEmpty());
            assertEquals(0, harness.repository.calls);
        }
    }

    private static final class Harness implements AutoCloseable {
        private final UUID target = UUID.randomUUID();
        private final String command = "role set " + target + " ADMIN Staff appointment";
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final List<Component> playerComponents = new CopyOnWriteArrayList<>();
        private final List<String> auditLogs = new CopyOnWriteArrayList<>();
        private final Queue<Runnable> replies = new ConcurrentLinkedQueue<>();
        private final Queue<Runnable> playerReplies = new ConcurrentLinkedQueue<>();
        private final Map<Player, AuthorizationSnapshot> authorities = new IdentityHashMap<>();
        private final List<Player> onlinePlayers = new ArrayList<>();
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1);
        private final Repository repository = new Repository();
        private final PlayerService service;
        private final ConsoleCommandSender console;
        private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();

        private Harness() throws Exception {
            repository.targetProfile = new PlayerProfile(target, "TargetPlayer",
                    Instant.parse("2026-09-26T12:00:00Z"), Instant.parse("2026-09-26T12:00:00Z"));
            console = (ConsoleCommandSender) Proxy.newProxyInstance(ConsoleCommandSender.class.getClassLoader(),
                    new Class<?>[]{ConsoleCommandSender.class}, (object, method, arguments) -> {
                        if (method.getName().equals("sendPlainMessage")) {
                            messages.add((String) arguments[0]);
                            return null;
                        }
                        throw new AssertionError("Unexpected console access");
                    });
            Logger logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    auditLogs.add(record.getMessage());
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
            service = worker.submit(() -> {
                var initialized = new PlayerService(repository, worker, deadlines,
                        message -> fail(message), logger, Clock.systemUTC(), PlayerServiceLimits.DEFAULT);
                initialized.startOnWorker();
                return initialized;
            }).get(2, TimeUnit.SECONDS);
            OmegaScheduler scheduler = new OmegaScheduler() {
                @Override
                public void global(Runnable task) {
                    replies.add(task);
                }

                @Override
                public Task repeatAsync(Duration delay, Duration period, Runnable task) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void close() {
                }
            };
            CommandAccess access = new CommandAccess(() -> console, () -> RuntimeState.READY,
                    player -> Optional.ofNullable(authorities.get(player)));
            CommandAudit audit = new CommandAudit(logger, "unit-server", () -> service);
            dispatcher.getRoot().addChild(new RoleCommand(access, () -> service, scheduler,
                    audit, logger, () -> fail("Unexpected scheduling failure"),
                    (player, reply) -> playerReplies.add(reply),
                    new OnlinePlayerTargetSuggestions(access, () -> onlinePlayers)).node(
                    StringArgumentType.word()));
        }

        private Player player() {
            return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (object, method, arguments) -> {
                        if (method.getName().equals("sendMessage") && arguments[0] instanceof Component component) {
                            playerComponents.add(component);
                            messages.add(PlainTextComponentSerializer.plainText().serialize(component));
                            return null;
                        }
                        throw new AssertionError("Unexpected player access: " + method.getName());
                    });
        }

        @Override
        public void close() throws Exception {
            repository.release.countDown();
            service.requestStop(Instant.now());
            try {
                worker.submit(service::closeOnWorker).get(3, TimeUnit.SECONDS);
            } finally {
                worker.shutdown();
                deadlines.shutdownNow();
                assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
                assertTrue(deadlines.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }

    private static final class Repository implements PlayerSessionRepository {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile int calls;
        private RoleChangeRequest request;
        private RoleChangeResult.Status resultStatus = RoleChangeResult.Status.CHANGED;
        private PlayerProfile targetProfile;
        private boolean ambiguousTarget;

        @Override
        public PlayerProfileRepository profiles() {
            return new StubPlayerProfileRepository();
        }

        @Override
        public PlayerTargetResolution resolveTarget(PlayerTarget target) {
            if (ambiguousTarget && target instanceof PlayerTarget.ByUsername) {
                return PlayerTargetResolution.ambiguous();
            }
            if (targetProfile != null && (target instanceof PlayerTarget.ByUuid(UUID uuid1)
                    && targetProfile.uuid().equals(uuid1)
                    || target instanceof PlayerTarget.ByUsername(String username)
                    && targetProfile.username().equalsIgnoreCase(username))) {
                return PlayerTargetResolution.found(new PublicPlayerProfile(targetProfile, Role.PLAYER));
            }
            return PlayerTargetResolution.missing();
        }

        @Override
        public void appendCommandAudit(com.omegarealm.core.command.CommandAuditEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RoleChangeResult changeRole(RoleChangeRequest request) throws PersistenceException {
            calls++;
            this.request = request;
            entered.countDown();
            try {
                assertTrue(release.await(3, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            AuthorizationSnapshot authorization =
                    new AuthorizationSnapshot(request.targetUuid(), request.role(), 1);
            return switch (resultStatus) {
                case CHANGED -> new RoleChangeResult(resultStatus, authorization, UUID.randomUUID());
                case UNCHANGED -> new RoleChangeResult(resultStatus, authorization, null);
                case DENIED, NOT_FOUND -> new RoleChangeResult(resultStatus, null, null);
            };
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

        @Override
        public Map<UUID, AuthorizationSnapshot> loadAuthorizations(List<UUID> ids) {
            return Map.of();
        }

        @Override
        public LoadedPlayer loadPlayer(UUID uuid) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PlayerProfile> findPlayer(UUID uuid) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PlayerSession> findSession(UUID uuid) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PlayerProfile join(SessionStart start) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean endSession(UUID id, Instant at, SessionEnd reason) {
            throw new UnsupportedOperationException();
        }
    }
}
