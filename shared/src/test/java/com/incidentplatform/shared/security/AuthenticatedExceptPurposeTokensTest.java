package com.incidentplatform.shared.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SharedSecurityAutoConfiguration#authenticatedExceptPurposeTokens()}
 * (backlog #0-16): {@code authenticated()} for everyone except an
 * {@link IntrospectionPrincipal}.
 */
@DisplayName("authenticatedExceptPurposeTokens")
class AuthenticatedExceptPurposeTokensTest {

    private final AuthorizationManager<RequestAuthorizationContext> manager =
            SharedSecurityAutoConfiguration.authenticatedExceptPurposeTokens();
    private final RequestAuthorizationContext context =
            new RequestAuthorizationContext(new MockHttpServletRequest("GET", "/api/v1/users"));

    private boolean granted(Authentication authentication) {
        return manager.authorize(() -> authentication, context).isGranted();
    }

    private static Authentication authenticated(Object principal) {
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    @Test
    @DisplayName("denies an IntrospectionPrincipal")
    void deniesIntrospection() {
        final IntrospectionPrincipal principal = new IntrospectionPrincipal("ingestion-service");
        assertThat(granted(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()))).isFalse();
    }

    @Test
    @DisplayName("grants a user and a tenant-bound service principal")
    void grantsOthers() {
        assertThat(granted(authenticated(new UserPrincipal(UUID.randomUUID(), "acme",
                "a@acme.test", List.of(SecurityRoles.ROLE_ADMIN), List.of(), List.of(), null))))
                .isTrue();
        assertThat(granted(authenticated(new ServicePrincipal("notification-service", "acme"))))
                .isTrue();
    }

    @Test
    @DisplayName("denies anonymous and missing authentication, like authenticated()")
    void deniesAnonymous() {
        assertThat(granted(new AnonymousAuthenticationToken("key", "anonymous",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")))).isFalse();
        assertThat(granted(null)).isFalse();
    }
}
