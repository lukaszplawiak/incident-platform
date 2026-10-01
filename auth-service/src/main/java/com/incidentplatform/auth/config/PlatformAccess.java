package com.incidentplatform.auth.config;

import com.incidentplatform.shared.security.ReservedTenants;
import com.incidentplatform.shared.security.SecurityRoles;
import com.incidentplatform.shared.security.UserPrincipal;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Who may use the platform API, /api/v1/platform/** (backlog #0-80): an admin
 * of the {@link ReservedTenants#PLATFORM_OPERATOR} tenant, logged in
 * interactively.
 *
 * <ul>
 *   <li>Tenant {@code platform-operator}: the tenant that exists for the
 *       platform's operators. An admin of any customer tenant is refused, however
 *       many rights they have in their own tenant.</li>
 *   <li>{@code ROLE_ADMIN} in that tenant.</li>
 *   <li>A JWT, not an API key: these are actions across tenant boundaries, and a
 *       long-lived key (a personal API key also yields a {@link UserPrincipal})
 *       must not be enough for them. Service tokens and purpose tokens carry other
 *       principal types and are refused too.</li>
 * </ul>
 * Enforced twice: in the filter chain ({@link #forRequests()}, SecurityConfig)
 * and on every controller method ({@code @PreAuthorize("@platformAccess.isPlatformAdmin(authentication)")}),
 * so a route added under /api/v1/platform without the annotation is still closed.
 */
@Component("platformAccess")
public class PlatformAccess {

    public boolean isPlatformAdmin(Authentication authentication) {
        return allows(authentication);
    }

    static boolean allows(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserPrincipal principal
                && !principal.isApiKey()
                && ReservedTenants.PLATFORM_OPERATOR.equals(principal.tenantId())
                && principal.hasRole(SecurityRoles.ROLE_ADMIN);
    }

    /** The same rule for the filter chain. */
    static AuthorizationManager<RequestAuthorizationContext> forRequests() {
        return new AuthorizationManager<>() {

            @Override
            public AuthorizationResult authorize(Supplier<Authentication> authentication,
                                                 RequestAuthorizationContext context) {
                return new AuthorizationDecision(allows(authentication.get()));
            }

            /** Required by the interface in Spring Security 6.x; delegates. */
            @Override
            @SuppressWarnings({"deprecation", "removal"})
            public AuthorizationDecision check(Supplier<Authentication> authentication,
                                               RequestAuthorizationContext context) {
                return (AuthorizationDecision) authorize(authentication, context);
            }
        };
    }
}
