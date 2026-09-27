package com.omegarealm.core.authorization.paper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GuardedCommandTreeTest {

    @Test
    void defaultTrueCommandIsHiddenAndRejectedWithoutAuthority() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var original = LiteralArgumentBuilder.<Boolean>literal("help").executes(context -> calls.incrementAndGet()).build();
        var dispatcher = new CommandDispatcher<Boolean>();
        dispatcher.getRoot().addChild(new GuardedCommandTree<Boolean>(Boolean::booleanValue, source -> {
        }).copy(original));
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("help", false));
        assertTrue(dispatcher.getCompletionSuggestions(dispatcher.parse("he", false)).get().isEmpty());
        assertEquals(0, calls.get());
        assertEquals(1, dispatcher.execute("help", true));
    }

    @Test
    void revocationAfterParsingStillPreventsExecutionAndSuggestions() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(true);
        AtomicInteger calls = new AtomicInteger();
        var original = LiteralArgumentBuilder.<String>literal("time")
                .then(RequiredArgumentBuilder.<String, String>argument("value", StringArgumentType.word())
                        .suggests((context, builder) -> builder.suggest("day").buildFuture())
                        .executes(context -> calls.incrementAndGet())).build();
        var dispatcher = new CommandDispatcher<String>();
        dispatcher.getRoot().addChild(new GuardedCommandTree<String>(source -> allowed.get(), source -> {
        }).copy(original));
        var execution = dispatcher.parse("time day", "player");
        var completion = dispatcher.parse("time d", "player");
        allowed.set(false);
        var denied = assertThrows(CommandSyntaxException.class, () -> dispatcher.execute(execution));
        assertEquals("time day", denied.getInput());
        assertEquals(0, denied.getCursor());
        assertTrue(dispatcher.getCompletionSuggestions(completion).get().isEmpty());
        assertEquals(0, calls.get());
    }

    @Test
    void namespaceRedirectRetainsNativeArgumentsAndGuardsTheRedirectedHandler() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(true);
        var original = LiteralArgumentBuilder.<String>literal("weather")
                .then(LiteralArgumentBuilder.<String>literal("clear").executes(context -> 7)).build();
        var alias = LiteralArgumentBuilder.<String>literal("minecraft:weather").redirect(original).build();
        var dispatcher = new CommandDispatcher<String>();
        dispatcher.getRoot().addChild(new GuardedCommandTree<String>(source -> allowed.get(), source -> {
        }).copy(alias));
        assertEquals(7, dispatcher.execute("minecraft:weather clear", "player"));
        var parsed = dispatcher.parse("minecraft:weather clear", "player");
        allowed.set(false);
        var denied = assertThrows(CommandSyntaxException.class, () -> dispatcher.execute(parsed));
        assertEquals("minecraft:weather clear", denied.getInput());
        assertTrue(denied.getCursor() >= 0);
    }

    @Test
    void legacyBranchesMaskBlockedToolsAndKeepConsoleGreedyArguments() throws Exception {
        var original = LiteralArgumentBuilder.<String>literal("paper").executes(context -> 1)
                .then(RequiredArgumentBuilder.<String, String>argument("args", StringArgumentType.greedyString())
                        .suggests((context, builder) -> builder.suggest("reload").buildFuture())
                        .executes(context -> 3)).build();
        var dispatcher = new CommandDispatcher<String>();
        dispatcher.getRoot().addChild(new GuardedCommandTree<String>(source -> true, source -> {
        })
                .branches(original, Set.of("fixlight", "version"), source -> source.equals("player")));
        assertEquals(3, dispatcher.execute("paper fixlight 2", "player"));
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("paper reload", "player"));
        var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("paper ", "player")).get();
        assertEquals(Set.of("fixlight", "version"), suggestions.getList().stream()
                .map(com.mojang.brigadier.suggestion.Suggestion::getText).collect(java.util.stream.Collectors.toSet()));
        assertEquals(3, dispatcher.execute("paper reload", "console"));
    }

    @Test
    void preservesOriginalRequirementsAndLogsOnlyActualExecutions() throws Exception {
        AtomicInteger invoked = new AtomicInteger();
        var original = LiteralArgumentBuilder.<Boolean>literal("tool").requires(Boolean::booleanValue)
                .executes(context -> 8).build();
        var dispatcher = new CommandDispatcher<Boolean>();
        dispatcher.getRoot().addChild(new GuardedCommandTree<Boolean>(source -> true, source -> invoked.incrementAndGet())
                .copy(original));
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("tool", false));
        assertEquals(0, invoked.get());
        assertEquals(8, dispatcher.execute("tool", true));
        assertEquals(1, invoked.get());
    }
}
