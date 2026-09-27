package com.omegarealm.core.authorization.paper;

import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionPlanTest {

    @Test
    void explicitlyDeniesDefaultTrueAndUnregisteredChildrenAfterTheirParents() {
        var parent = new Permission("parent", PermissionDefault.TRUE, Map.of("child", true, "inverted", false));
        var child = new Permission("child", PermissionDefault.OP, Map.of("leaf", true));
        var order = PermissionPlan.order(List.of(parent, child));
        assertTrue(order.indexOf("parent") < order.indexOf("child"));
        assertTrue(order.indexOf("child") < order.indexOf("leaf"));
        assertTrue(order.indexOf("parent") < order.indexOf("inverted"));
        assertTrue(order.contains("minecraft.command.gamerule"));
    }

    @Test
    void cyclicPermissionGraphFailsClosedInsteadOfRecursingInsideBukkit() {
        var first = new Permission("first", Map.of("second", false));
        var second = new Permission("second", Map.of("first", true));
        assertThrows(IllegalStateException.class, () -> PermissionPlan.order(List.of(first, second)));
    }
}
