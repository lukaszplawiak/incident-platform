package com.incidentplatform.auth.config;

import com.incidentplatform.shared.security.ReservedTenants;
import com.incidentplatform.shared.security.SecurityRoles;
import com.incidentplatform.shared.security.UserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The platform API's access rule (backlog #0-80). The filter-chain form is
 * tested on its own because the controller's {@code @PreAuthorize} would hide a
 * hole in it: it is what keeps a route added without the annotation closed.
 */
@DisplayName("PlatformAccess")
class PlatformAccessTest {

    private final PlatformAccess access = new PlatformAccess();

    private static Authentication user(String tenantId, boolean apiKey, String... roles) {
        final UserPrincipal principal = new UserPrincipal(UUID.randomUUID(), tenantId,
                "ops@platform.test", List.of(roles), List.of(), List.of(), apiKey, List.of(), null);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private static boolean chainAllows(Authentication authentication) {
        return PlatformAccess.forRequests()
                .authorize(() -> authentication,
                        new RequestAuthorizationContext(new MockHttpServletRequest()))
                .isGranted();
    }

    @Test
    @DisplayName("allows an operator-tenant admin with a JWT, in both forms")
    void allowsOperatorAdmin() {
        final Authentication operatorAdmin =
                user(ReservedTenants.PLATFORM_OPERATOR, false, SecurityRoles.ROLE_ADMIN);

        assertThat(access.isPlatformAdmin(operatorAdmin)).isTrue();
        assertThat(chainAllows(operatorAdmin)).isTrue();
    }

    @Test
    @DisplayName("refuses every other principal, in both forms")
    void refusesEveryoneElse() {
        final List<Authentication> refused = List.of(
                user("acme", false, SecurityRoles.ROLE_ADMIN),
                user(ReservedTenants.LEGACY_SYSTEM, false, SecurityRoles.ROLE_ADMIN),
                user(ReservedTenants.PLATFORM_OPERATOR, false, SecurityRoles.ROLE_RESPONDER),
                user(ReservedTenants.PLATFORM_OPERATOR, true, SecurityRoles.ROLE_ADMIN),
                new UsernamePasswordAuthenticationToken("ops", null,
                        AuthorityUtils.createAuthorityList(SecurityRoles.ROLE_ADMIN)),
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        for (final Authentication authentication : refused) {
            assertThat(access.isPlatformAdmin(authentication)).as(authentication.toString()).isFalse();
            assertThat(chainAllows(authentication)).as(authentication.toString()).isFalse();
        }
        assertThat(access.isPlatformAdmin(null)).isFalse();
        assertThat(chainAllows(null)).isFalse();
    }

    @Test
    @DisplayName("refuses an unauthenticated token carrying an operator admin principal")
    void refusesUnauthenticated() {
        final UserPrincipal principal = new UserPrincipal(UUID.randomUUID(),
                ReservedTenants.PLATFORM_OPERATOR, "ops@platform.test",
                List.of(SecurityRoles.ROLE_ADMIN), List.of());
        final Authentication unauthenticated =
                UsernamePasswordAuthenticationToken.unauthenticated(principal, null);

        assertThat(access.isPlatformAdmin(unauthenticated)).isFalse();
        assertThat(chainAllows(unauthenticated)).isFalse();
    }
}
