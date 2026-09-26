package com.omegarealm.core.lifecycle;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.omegarealm.core.RuntimeState;
import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AdmissionGuardTest {

    private static AsyncPlayerPreLoginEvent preLogin() {
        InetAddress address = InetAddress.getLoopbackAddress();
        return new AsyncPlayerPreLoginEvent("TestPlayer", address, address, UUID.randomUUID(), false,
                untouched(PlayerProfile.class), "localhost", untouched(PlayerLoginConnection.class));
    }

    private static PlayerConnectionValidateLoginEvent validation() {
        return new PlayerConnectionValidateLoginEvent(untouched(PlayerLoginConnection.class), null);
    }

    private static <T> T untouched(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> {
                    throw new AssertionError("The admission guard must not access player or connection state");
                }));
    }

    @ParameterizedTest
    @EnumSource(RuntimeState.class)
    void allowsAdmissionOnlyWhenReady(RuntimeState state) {
        AdmissionGuard guard = new AdmissionGuard(() -> state);
        AsyncPlayerPreLoginEvent preLogin = preLogin();
        PlayerConnectionValidateLoginEvent validation = validation();

        guard.onPreLogin(preLogin);
        guard.onValidateLogin(validation);

        assertEquals(state == RuntimeState.READY, preLogin.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED);
        assertEquals(state == RuntimeState.READY, validation.isAllowed());
    }

    @Test
    void rechecksReadinessAtLaterLoginValidation() {
        AtomicReference<RuntimeState> state = new AtomicReference<>(RuntimeState.READY);
        AdmissionGuard guard = new AdmissionGuard(state::get);
        AsyncPlayerPreLoginEvent preLogin = preLogin();
        guard.onPreLogin(preLogin);
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, preLogin.getLoginResult());

        state.set(RuntimeState.STOPPING);
        PlayerConnectionValidateLoginEvent validation = validation();
        guard.onValidateLogin(validation);

        assertFalse(validation.isAllowed());
    }

    @Test
    void readinessDoesNotOverrideOtherAdmissionRestrictions() {
        AdmissionGuard guard = new AdmissionGuard(() -> RuntimeState.READY);
        Component banned = Component.text("Existing restriction");
        AsyncPlayerPreLoginEvent preLogin = preLogin();
        preLogin.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, banned);
        PlayerConnectionValidateLoginEvent validation = validation();
        validation.kickMessage(banned);

        guard.onPreLogin(preLogin);
        guard.onValidateLogin(validation);

        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, preLogin.getLoginResult());
        assertSame(banned, preLogin.kickMessage());
        assertSame(banned, validation.getKickMessage());
    }
}
