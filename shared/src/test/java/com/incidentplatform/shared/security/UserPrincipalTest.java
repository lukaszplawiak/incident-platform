package com.incidentplatform.shared.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UserPrincipal")
class UserPrincipalTest {

    private static final UUID USER_ID  = UUID.randomUUID();
    private static final String TENANT = "acme-corp";
    private static final String EMAIL  = "user@acme.com";

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("should default to empty list when roles is null")
        void shouldDefaultToEmptyListWhenRolesNull() {
            final UserPrincipal principal =
                    new UserPrincipal(USER_ID, TENANT, EMAIL, null, List.of());

            assertThat(principal.roles()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("should defensively copy roles list — caller mutation must not affect principal")
        void shouldDefensivelyCopyRoles() {
            // Covers actual logic in the compact constructor (List.copyOf).
            // Without this test, someone could "simplify" it to a direct
            // assignment and silently reintroduce shared mutable state.
            final List<String> mutableRoles = new ArrayList<>();
            mutableRoles.add(SecurityRoles.ROLE_RESPONDER);

            final UserPrincipal principal =
                    new UserPrincipal(USER_ID, TENANT, EMAIL, mutableRoles, List.of());

            mutableRoles.add(SecurityRoles.ROLE_ADMIN);

            assertThat(principal.roles()).containsExactly(SecurityRoles.ROLE_RESPONDER);
        }
    }

    @Nested
    @DisplayName("getAuthorities")
    class GetAuthorities {

        @Test
        @DisplayName("should map each role to a SimpleGrantedAuthority")
        void shouldMapRolesToAuthorities() {
            final UserPrincipal principal = new UserPrincipal(
                    USER_ID, TENANT, EMAIL, List.of(SecurityRoles.ROLE_ADMIN, SecurityRoles.ROLE_RESPONDER), List.of());

            final List<String> authorityNames = principal.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .toList();

            assertThat(authorityNames).containsExactlyInAnyOrder(
                    SecurityRoles.ROLE_ADMIN, SecurityRoles.ROLE_RESPONDER);
        }
    }

    @Nested
    @DisplayName("hasRole")
    class HasRole {

        @Test
        @DisplayName("should return true when role is present")
        void shouldReturnTrueWhenRolePresent() {
            final UserPrincipal principal = new UserPrincipal(
                    USER_ID, TENANT, EMAIL, List.of(SecurityRoles.ROLE_ADMIN), List.of());

            assertThat(principal.hasRole(SecurityRoles.ROLE_ADMIN)).isTrue();
        }

        @Test
        @DisplayName("should return false when role is absent")
        void shouldReturnFalseWhenRoleAbsent() {
            final UserPrincipal principal = new UserPrincipal(
                    USER_ID, TENANT, EMAIL, List.of(SecurityRoles.ROLE_RESPONDER), List.of());

            assertThat(principal.hasRole(SecurityRoles.ROLE_ADMIN)).isFalse();
        }
    }

    @Nested
    @DisplayName("does not implement UserDetails")
    class DoesNotImplementUserDetails {

        @Test
        @DisplayName("should not implement Spring Security UserDetails interface")
        void shouldNotImplementUserDetails() {
            // Regression guard: UserPrincipal previously implemented UserDetails
            // with 5 no-op methods that were never called (no AuthenticationManager
            // / UserDetailsService in this stateless JWT architecture). This test
            // documents the architectural decision and fails loudly if someone
            // reintroduces the interface without re-evaluating the need for it.
            assertThat(UserPrincipal.class.getInterfaces())
                    .as("UserPrincipal should not implement UserDetails — " +
                            "see class Javadoc for rationale")
                    .noneMatch(i -> i.getSimpleName().equals("UserDetails"));
        }

        @Test
        @DisplayName("should not declare any of the removed no-op UserDetails methods")
        void shouldNotDeclareUserDetailsMethods() {
            final List<String> forbiddenMethodNames = List.of(
                    "getPassword", "getUsername",
                    "isAccountNonExpired", "isAccountNonLocked",
                    "isCredentialsNonExpired", "isEnabled");

            final List<String> declaredMethodNames = Arrays
                    .stream(UserPrincipal.class.getDeclaredMethods())
                    .map(Method::getName)
                    .toList();

            assertThat(declaredMethodNames)
                    .doesNotContainAnyElementsOf(forbiddenMethodNames);
        }
    }
}