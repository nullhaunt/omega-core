package com.omegarealm.core.authorization;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NativeCommandCatalogTest {

    @Test
    void grantsReviewedToolsOnlyToManagementIncludingTheirNativeNamespaces() {
        for (String label : List.of("paper", "paper:paper", "spark", "paper:spark", "tps", "spigot:tps",
                "bukkit:mspt", "bukkit:about", "time", "minecraft:worldborder", "save-all", "whitelist")) {
            assertTrue(NativeCommandCatalog.allows(Role.ADMIN, label), label);
            assertTrue(NativeCommandCatalog.allows(Role.OWNER, label), label);
            assertFalse(NativeCommandCatalog.allows(Role.PLAYER, label), label);
            assertFalse(NativeCommandCatalog.allows(Role.MODERATOR, label), label);
        }
    }

    @Test
    void rejectsOtherNamespacesAutomationModerationAndLifecycleCommands() {
        for (String label : List.of("stop", "minecraft:stop", "restart", "spigot:restart", "reload", "bukkit:rl",
                "op", "minecraft:op", "execute", "function", "schedule", "datapack", "kick", "ban",
                "save-off", "save-on", "timings", "gamemode", "give", "other:paper", "paper:time")) {
            assertFalse(NativeCommandCatalog.allows(Role.OWNER, label), label);
        }
    }

    @Test
    void grantsNoParentsWildcardsOrDefaultGameplayPermissions() {
        var grants = NativeCommandCatalog.permissions(Role.OWNER);
        assertTrue(grants.contains("minecraft.command.gamerule"));
        assertTrue(grants.contains("bukkit.command.paper.fixlight"));
        assertTrue(grants.contains("spark.profiler"));
        for (String permission : List.of("minecraft", "minecraft.command", "bukkit.command.paper", "spark",
                "minecraft.nbt.copy", "minecraft.command.op", "minecraft.command.stop", "spark.*")) {
            assertFalse(grants.contains(permission), permission);
        }
        assertEquals(NativeCommandCatalog.permissions(Role.ADMIN), grants);
        assertTrue(NativeCommandCatalog.permissions(Role.MODERATOR).isEmpty());
    }

    @Test
    void mapsAliasesToActualLeafPermissionAndOmitsPaperReload() {
        assertEquals("profiler", NativeCommandCatalog.branches("paper:spark").get("sampler"));
        assertEquals("health", NativeCommandCatalog.branches("spark").get("healthreport"));
        assertEquals("version", NativeCommandCatalog.branches("paper").get("ver"));
        assertFalse(NativeCommandCatalog.branches("paper").containsKey("reload"));
    }
}
