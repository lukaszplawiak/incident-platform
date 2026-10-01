package com.incidentplatform.auth.config;

import com.incidentplatform.auth.service.MfaSessionStatusService;
import com.incidentplatform.shared.security.ReservedTenants;
import com.incidentplatform.shared.security.SecurityRoles;
import com.incidentplatform.shared.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.function.Supplier;

/**
 * Who may use the platform API, /api/v1/platform/** (backlog #0-80): an admin
 * of the {@link ReservedTenants#PLATFORM_OPERATOR} tenant, logged in
 * interactively, in a session that completed MFA.
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
 *   <li>Added (backlog #0-83): the token's login session completed a TOTP or
 *       backup code recently, with a factor the account has had for a grace
 *       period, and is still live ({@link MfaSessionStatusService} has the
 *       three conditions and why). A phished or reused operator password
 *       alone no longer reaches the platform's one cross-tenant capability.
 *       Checked on the server, per request, from the session the access token
 *       names, so logging out or disabling MFA takes effect at once.</li>
 * </ul>
 * Enforced twice: in the filter chain ({@link #forRequests()}, SecurityConfig)
 * and on every controller method ({@code @PreAuthorize("@platformAccess.isPlatformAdmin(authentication)")}),
 * so a route added under /api/v1/platform without the annotation is still closed.
 * When only the MFA requirement fails, the filter chain marks the request
 * with the reason ({@link #MFA_REQUIRED_ATTRIBUTE}) so
 * {@link PlatformAccessDeniedHandler} can say which condition failed instead
 * of a bare 403.
 *
 * <p>The two enforcement points each run the check, so an allowed request
 * looks the session up twice. Accepted: two indexed queries on a rarely used,
 * operator-only API, and sharing one result between the filter chain and
 * {@code @PreAuthorize} would add request-scoped state to a security
 * decision for no measurable gain.
 *
 * <p>Declared as a bean named {@code platformAccess} in {@link SecurityConfig}
 * (the name {@code @PreAuthorize} refers to), next to the filter chain that
 * uses it, so every test slice that loads the security configuration gets it.
 */
public class PlatformAccess {

    private static final Logger log = LoggerFactory.getLogger(PlatformAccess.class);

    /**
     * Request attribute set when an operator admin is refused only for the MFA
     * requirement; its value is the {@link MfaSessionStatusService.Status}.
     */
    public static final String MFA_REQUIRED_ATTRIBUTE = PlatformAccess.class.getName() + ".MFA_REQUIRED";

    /** The outcome of the rule for one caller. */
    enum Decision { ALLOWED, DENIED, MFA_REQUIRED }

    private final MfaSessionStatusService mfaSessionStatus;

    public PlatformAccess(MfaSessionStatusService mfaSessionStatus) {
        this.mfaSessionStatus = mfaSessionStatus;
    }

    /** For {@code @PreAuthorize}. */
    public boolean isPlatformAdmin(Authentication authentication) {
        return decide(authentication) == Decision.ALLOWED;
    }

    Decision decide(Authentication authentication) {
        return evaluate(authentication).decision();
    }

    private record Evaluation(Decision decision, MfaSessionStatusService.Status mfaStatus) {
    }

    private Evaluation evaluate(Authentication authentication) {
        if (!isOperatorAdminWithJwt(authentication)) {
            return new Evaluation(Decision.DENIED, null);
        }
        final UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        final MfaSessionStatusService.Status status =
                mfaSessionStatus.check(principal.userId(), principal.tenantId(), principal.sessionId());
        if (status != MfaSessionStatusService.Status.ACCEPTED) {
            log.warn("Platform API refused: MFA requirement not met ({}), userId={}, sessionId={}",
                    status, principal.userId(), principal.sessionId());
            return new Evaluation(Decision.MFA_REQUIRED, status);
        }
        return new Evaluation(Decision.ALLOWED, status);
    }

    static boolean isOperatorAdminWithJwt(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserPrincipal principal
                && !principal.isApiKey()
                && ReservedTenants.PLATFORM_OPERATOR.equals(principal.tenantId())
                && principal.hasRole(SecurityRoles.ROLE_ADMIN);
    }

    /** The same rule for the filter chain. */
    AuthorizationManager<RequestAuthorizationContext> forRequests() {
        return new AuthorizationManager<>() {

            @Override
            public AuthorizationResult authorize(Supplier<Authentication> authentication,
                                                 RequestAuthorizationContext context) {
                final Evaluation evaluation = evaluate(authentication.get());
                if (evaluation.decision() == Decision.MFA_REQUIRED) {
                    context.getRequest().setAttribute(MFA_REQUIRED_ATTRIBUTE, evaluation.mfaStatus());
                }
                return new AuthorizationDecision(evaluation.decision() == Decision.ALLOWED);
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
