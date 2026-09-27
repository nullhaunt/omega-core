package com.omegarealm.core.authorization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizationPolicyTest {

    private static final UUID ACTOR_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TARGET_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final List<Role> ROLES = List.of(Role.PLAYER, Role.MODERATOR, Role.ADMIN, Role.OWNER);

    private static Stream<Arguments> playerRoleChanges() {
        // Rows are target roles; columns are requested roles, in ROLES order.
        // A same-role request still requires authority; it may become an idempotent
        // no-op in the mutation service, never a bypass of the policy.
        List<List<String>> allowed = List.of(
                List.of("0000", "0000", "0000", "0000"),
                List.of("0000", "0000", "0000", "0000"),
                List.of("1100", "1100", "0000", "0000"),
                List.of("1110", "1110", "1110", "0000"));

        Stream.Builder<Arguments> cases = Stream.builder();
        for (int actor = 0; actor < ROLES.size(); actor++) {
            for (int current = 0; current < ROLES.size(); current++) {
                for (int requested = 0; requested < ROLES.size(); requested++) {
                    boolean expected = allowed.get(actor).get(current).charAt(requested) == '1';
                    cases.add(Arguments.of(ROLES.get(actor), ROLES.get(current), ROLES.get(requested), expected));
                }
            }
        }

        return cases.build();
    }

    private static Stream<Arguments> roleChanges() {
        return ROLES.stream().flatMap(current -> ROLES.stream()
                .map(requested -> Arguments.of(current, requested)));
    }

    private static AuthorizationActor.Player player(UUID uuid, Role role) {
        return new AuthorizationActor.Player(uuid, role);
    }

    @ParameterizedTest
    @MethodSource("playerRoleChanges")
    void playerRoleChangesFollowTheCompleteAuthorityMatrix(Role actorRole, Role currentRole,
                                                           Role requestedRole, boolean expected) {
        AuthorizationActor.Player actor = player(ACTOR_UUID, actorRole);
        AuthorizationActor.Player target = player(TARGET_UUID, currentRole);

        assertEquals(expected, AuthorizationPolicy.canChangeRole(actor, target, requestedRole));
    }

    @ParameterizedTest
    @MethodSource("roleChanges")
    void onlyLocalConsoleMayManageEveryRole(Role currentRole, Role requestedRole) {
        AuthorizationActor.Player target = player(TARGET_UUID, currentRole);

        assertTrue(AuthorizationPolicy.canChangeRole(new AuthorizationActor.LocalConsole(), target, requestedRole));
        assertFalse(AuthorizationPolicy.canChangeRole(new AuthorizationActor.Untrusted(), target, requestedRole));
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void playersCannotChangeTheirOwnRoleEvenWithInconsistentTargetMetadata(Role actorRole) {
        AuthorizationActor.Player actor = player(ACTOR_UUID, actorRole);

        for (Role currentRole : ROLES) {
            for (Role requestedRole : ROLES) {
                assertFalse(AuthorizationPolicy.canChangeRole(actor, player(ACTOR_UUID, currentRole), requestedRole));
            }
        }
    }

    @Test
    void capabilitySetsGrantOnlyTheExplicitRoleManagementActions() {
        assertEquals(Set.of(), Role.PLAYER.capabilities());
        assertEquals(Set.of(), Role.MODERATOR.capabilities());
        assertEquals(Set.of(Capability.MANAGE_MODERATOR_ROLE), Role.ADMIN.capabilities());
        assertEquals(Set.of(Capability.MANAGE_MODERATOR_ROLE, Capability.MANAGE_ADMIN_ROLE),
                Role.OWNER.capabilities());

        for (Role role : ROLES) {
            AuthorizationActor.Player actor = player(ACTOR_UUID, role);
            for (Capability capability : Capability.values()) {
                boolean expected = role.capabilities().contains(capability);
                assertEquals(expected, AuthorizationPolicy.allows(actor, capability));
                assertEquals(expected, AuthorizationPolicy.allows(actor, capability.key()));
            }
            assertFalse(AuthorizationPolicy.allows(actor, Capability.MANAGE_OWNER_ROLE));
            assertThrows(UnsupportedOperationException.class,
                    () -> role.capabilities().add(Capability.MANAGE_OWNER_ROLE));
        }
    }

    @ParameterizedTest
    @EnumSource(Capability.class)
    void trustedConsoleAndUntrustedSourcesHaveDistinctAuthority(Capability capability) {
        assertTrue(AuthorizationPolicy.allows(new AuthorizationActor.LocalConsole(), capability));
        assertTrue(AuthorizationPolicy.allows(new AuthorizationActor.LocalConsole(), capability.key()));
        assertFalse(AuthorizationPolicy.allows(new AuthorizationActor.Untrusted(), capability));
        assertFalse(AuthorizationPolicy.allows(new AuthorizationActor.Untrusted(), capability.key()));
        assertEquals(capability, Capability.fromKey(capability.key()).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "*", "omega.admin.*", "omega.admin.roles", "minecraft.command.op",
            "omega.admin.roles.admin ", " omega.admin.roles.admin", "OMEGA.ADMIN.ROLES.ADMIN"})
    void unknownOrNonExactCapabilityKeysDenyEvenForConsole(String key) {
        assertTrue(Capability.fromKey(key).isEmpty());
        assertFalse(AuthorizationPolicy.allows(new AuthorizationActor.LocalConsole(), key));
        assertFalse(AuthorizationPolicy.allows(new AuthorizationActor.Untrusted(), key));

        for (Role role : ROLES) {
            assertFalse(AuthorizationPolicy.allows(player(ACTOR_UUID, role), key));
        }
    }

    @Test
    void roleInheritanceIsExplicitAndEveryRoleIncludesPlayer() {
        for (int authority = 0; authority < ROLES.size(); authority++) {
            Role role = ROLES.get(authority);
            assertTrue(role.includes(Role.PLAYER));

            for (int other = 0; other < ROLES.size(); other++) {
                assertEquals(authority >= other, role.includes(ROLES.get(other)));
                assertEquals(authority > other, role.isAbove(ROLES.get(other)));
            }
        }
    }

    @Test
    void missingIdentityOrRoleCannotConstructAPlayerActor() {
        assertThrows(NullPointerException.class, () -> player(null, Role.PLAYER));
        assertThrows(NullPointerException.class, () -> player(ACTOR_UUID, null));
    }
}
