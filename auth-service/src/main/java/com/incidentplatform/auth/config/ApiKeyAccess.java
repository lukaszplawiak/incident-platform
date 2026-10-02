package com.incidentplatform.auth.config;

import com.incidentplatform.auth.domain.ApiKeyScope;
import com.incidentplatform.shared.security.UserPrincipal;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.function.Supplier;

/**
 * Where an API key may go in auth-service (backlog #0-89): only to the
 * routes listed in {@link SecurityConfig}, each naming the scope the key must
 * carry; every other route refuses it, whatever roles the key's owner has.
 *
 * <h2>Why deny by default</h2>
 * Until #0-89 auth-service checked no scope: {@code ApiKeyLookupServiceImpl}
 * gives a personal key its owner's current roles, so an admin's personal key
 * was a full admin session without an end. With it, whoever had the admin's
 * password for a moment could invite a second admin, create a tenant key or
 * an integration, change roles or the tenant's MFA policy: credentials of
 * their own that no password or MFA reset of the owner takes back. A machine
 * credential has no business managing identities or credentials, so only an
 * interactive login does that here, and a key
 * reaches only what its scopes name. The pattern is the purpose token's
 * ({@code SharedSecurityAutoConfiguration.authenticatedExceptPurposeTokens},
 * backlog #0-16): one rule at the end of the chain refuses by default, so a
 * route added later is closed to keys until someone lists it.
 *
 * <h2>Scope and role</h2>
 * A listed route still runs its own {@code @PreAuthorize} role check: a key
 * needs both its scope and its owner's role (a tenant key acts as
 * {@code ROLE_RESPONDER}). The scope-to-role mapping a personal key is
 * checked against at creation is #0-46. {@code teams:write} is everything
 * the team routes allow, memberships and team roles included (found in
 * review, accepted): team membership routes notifications, it grants no
 * account, role or credential, and a recovery revokes the personal key.
 * A key cannot list or revoke API keys either, its own included: that is
 * done from a login.
 *
 * <p>Other principals pass straight to the rule given, as before.
 */
public final class ApiKeyAccess {

    private ApiKeyAccess() {
    }

    /**
     * A key is allowed when it carries {@code scope}; any other principal is
     * decided by {@code otherwise}.
     */
    public static AuthorizationManager<RequestAuthorizationContext> scopeOrElse(
            ApiKeyScope scope, AuthorizationManager<RequestAuthorizationContext> otherwise) {
        final String scopeName = scope.getScopeName();
        return decideKeys(apiKey -> apiKey.hasScope(scopeName), otherwise);
    }

    /** A key is refused; any other principal is decided by {@code otherwise}. */
    public static AuthorizationManager<RequestAuthorizationContext> deniedOrElse(
            AuthorizationManager<RequestAuthorizationContext> otherwise) {
        return decideKeys(apiKey -> false, otherwise);
    }

    /** Whether the caller authenticated with an API key. */
    static boolean isApiKey(Authentication authentication) {
        return authentication != null
                && authentication.getPrincipal() instanceof UserPrincipal principal
                && principal.isApiKey();
    }

    private interface KeyRule {
        boolean allows(UserPrincipal apiKey);
    }

    private static AuthorizationManager<RequestAuthorizationContext> decideKeys(
            KeyRule keyRule, AuthorizationManager<RequestAuthorizationContext> otherwise) {
        return new AuthorizationManager<>() {

            @Override
            public AuthorizationResult authorize(Supplier<Authentication> authentication,
                                                 RequestAuthorizationContext context) {
                final Authentication current = authentication.get();
                if (isApiKey(current)) {
                    return new AuthorizationDecision(keyRule.allows((UserPrincipal) current.getPrincipal()));
                }
                return otherwise.authorize(authentication, context);
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
