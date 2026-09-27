package com.omegarealm.core.authorization.paper;

import io.papermc.paper.ServerBuildInfo;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeCommandFenceTest {

    private static ServerBuildInfo build(String brand, String version, OptionalInt number) {
        return (ServerBuildInfo) Proxy.newProxyInstance(ServerBuildInfo.class.getClassLoader(),
                new Class<?>[]{ServerBuildInfo.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "brandId" -> Key.key(brand);
                    case "minecraftVersionId" -> version;
                    case "buildNumber" -> number;
                    default -> throw new AssertionError(method.getName());
                });
    }

    @Test
    void acceptsPaper26_3AcrossDevelopmentBuilds() {
        for (int number : new int[]{42, 43, 100}) {
            assertTrue(NativeCommandFence.targetsPaper26_3(build("papermc:paper", "26.3", OptionalInt.of(number))));
        }
        assertTrue(NativeCommandFence.targetsPaper26_3(build("papermc:paper", "26.3", OptionalInt.empty())));
    }

    @Test
    void refusesDifferentMinecraftVersionsAndUnsupportedBrands() {
        assertFalse(NativeCommandFence.targetsPaper26_3(build("papermc:paper", "26.2", OptionalInt.of(42))));
        assertFalse(NativeCommandFence.targetsPaper26_3(build("papermc:folia", "26.3", OptionalInt.of(42))));
    }
}
