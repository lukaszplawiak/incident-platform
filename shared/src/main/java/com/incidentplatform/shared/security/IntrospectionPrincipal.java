package com.incidentplatform.shared.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.security.Principal;
import java.util.Collection;
import java.util.List;

/**
 * Principal of a {@link TokenPurposes#API_KEY_INTROSPECTION} token: a service
 * asking auth-service which tenant an Integration API key belongs to
 * (backlog #0-16).
 *
 * <p>Deliberately carries <em>no tenant</em> and is not a
 * {@link ServicePrincipal}: {@link JwtAuthFilter} never sets
 * {@link TenantContext} for it, so any tenant-scoped code it reached would fail
 * on {@link TenantContext#get()} instead of silently acting for some tenant.
 * Its only authority is {@link SecurityRoles#ROLE_API_KEY_INTROSPECTION}, and
 * {@link SharedSecurityAutoConfiguration#authenticatedExceptPurposeTokens()}
 * keeps it off every route that is merely {@code authenticated()}.
 *
 * @param callerService name of the calling service (the token's {@code sub})
 */
public record IntrospectionPrincipal(String callerService) implements Principal {

    public IntrospectionPrincipal {
        if (callerService == null || callerService.isBlank()) {
            throw new IllegalArgumentException("callerService must not be blank");
        }
    }

    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(
                SecurityRoles.ROLE_API_KEY_INTROSPECTION));
    }

    @Override
    public String getName() {
        return callerService;
    }
}
