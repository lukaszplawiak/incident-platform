package com.incidentplatform.auth.config;

import com.incidentplatform.auth.service.MfaSessionStatusService;
import com.incidentplatform.auth.service.MfaSessionStatusService.Status;
import com.incidentplatform.shared.security.ReservedTenants;
import com.incidentplatform.shared.security.SecurityRoles;
import com.incidentplatform.shared.security.UserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The platform API's access rule (backlog #0-80, MFA since #0-83). The
 * filter-chain form is tested on its own because the controller's
 * {@code @PreAuthorize} would hide a hole in it: it is what keeps a route added
 * without the annotation closed.
 */
@DisplayName("PlatformAccess")
class PlatformAccessTest {

    private static final UUID SESSION = UUID.randomUUID();

    private final MfaSessionStatusService mfaSessions = mock(MfaSessionStatusService.class);
    private final PlatformAccess access = new PlatformAccess(mfaSessions);

    private static Authentication user(String tenantId, boolean apiKey, UUID sessionId, String... roles) {
        final UserPrincipal principal = new UserPrincipal(UUID.randomUUID(), tenantId,
                "ops@platform.test", List.of(roles), List.of(), List.of(), apiKey, List.of(), sessionId);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private static Authentication operatorAdmin() {
        return user(ReservedTenants.PLATFORM_OPERATOR, false, SESSION, SecurityRoles.ROLE_ADMIN);
    }

    private static UUID userIdOf(Authentication authentication) {
        return ((UserPrincipal) authentication.getPrincipal()).userId();
    }

    private boolean chainAllows(Authentication authentication, MockHttpServletRequest request) {
        return access.forRequests()
                .authorize(() -> authentication, new RequestAuthorizationContext(request))
                .isGranted();
    }

    @Test
    @DisplayName("allows an operator-tenant admin with a JWT whose session completed MFA, in both forms")
    void allowsOperatorAdminWithMfa() {
        final Authentication operatorAdmin = operatorAdmin();
        given(mfaSessions.check(userIdOf(operatorAdmin), ReservedTenants.PLATFORM_OPERATOR, SESSION)).willReturn(Status.ACCEPTED);
        final MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(access.isPlatformAdmin(operatorAdmin)).isTrue();
        assertThat(chainAllows(operatorAdmin, request)).isTrue();
        assertThat(request.getAttribute(PlatformAccess.MFA_REQUIRED_ATTRIBUTE)).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = "ACCEPTED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("refuses an operator-tenant admin whose session fails an MFA condition, and marks the request with it")
    void refusesOperatorAdminWithoutAcceptedMfa(Status status) {
        final Authentication operatorAdmin = operatorAdmin();
        given(mfaSessions.check(any(), any(), any())).willReturn(status);
        final MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(access.decide(operatorAdmin)).isEqualTo(PlatformAccess.Decision.MFA_REQUIRED);
        assertThat(access.isPlatformAdmin(operatorAdmin)).isFalse();
        assertThat(chainAllows(operatorAdmin, request)).isFalse();
        assertThat(request.getAttribute(PlatformAccess.MFA_REQUIRED_ATTRIBUTE)).isEqualTo(status);
    }

    @Test
    @DisplayName("refuses every other principal without asking about MFA or marking the request")
    void refusesEveryoneElse() {
        final List<Authentication> refused = List.of(
                user("acme", false, SESSION, SecurityRoles.ROLE_ADMIN),
                user(ReservedTenants.LEGACY_SYSTEM, false, SESSION, SecurityRoles.ROLE_ADMIN),
                user(ReservedTenants.PLATFORM_OPERATOR, false, SESSION, SecurityRoles.ROLE_RESPONDER),
                user(ReservedTenants.PLATFORM_OPERATOR, true, null, SecurityRoles.ROLE_ADMIN),
                new UsernamePasswordAuthenticationToken("ops", null,
                        AuthorityUtils.createAuthorityList(SecurityRoles.ROLE_ADMIN)),
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        for (final Authentication authentication : refused) {
            final MockHttpServletRequest request = new MockHttpServletRequest();
            assertThat(access.decide(authentication)).as(authentication.toString())
                    .isEqualTo(PlatformAccess.Decision.DENIED);
            assertThat(chainAllows(authentication, request)).as(authentication.toString()).isFalse();
            assertThat(request.getAttribute(PlatformAccess.MFA_REQUIRED_ATTRIBUTE)).isNull();
        }
        assertThat(access.isPlatformAdmin(null)).isFalse();
        verifyNoInteractions(mfaSessions);
    }

    @Test
    @DisplayName("refuses an unauthenticated token carrying an operator admin principal")
    void refusesUnauthenticated() {
        final UserPrincipal principal = new UserPrincipal(UUID.randomUUID(),
                ReservedTenants.PLATFORM_OPERATOR, "ops@platform.test",
                List.of(SecurityRoles.ROLE_ADMIN), List.of(), List.of(), SESSION);
        final Authentication unauthenticated =
                UsernamePasswordAuthenticationToken.unauthenticated(principal, null);

        assertThat(access.isPlatformAdmin(unauthenticated)).isFalse();
        verifyNoInteractions(mfaSessions);
    }
}
