package com.incidentplatform.shared.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Authenticated principal built from either JWT claims or API key lookup.
 *
 * <h2>Two authentication paths</h2>
 * <ul>
 *   <li><b>JWT</b> — built by {@link JwtAuthFilter} from token claims.
 *       {@link #isApiKey} = false, {@link #scopes} = empty.</li>
 *   <li><b>API Key</b> — built by {@code ApiKeyAuthFilter} from DB lookup.
 *       {@link #isApiKey} = true, {@link #scopes} = granted key scopes,
 *       {@link #roles} = owner roles (TENANT key) or owner roles (PERSONAL key).</li>
 * </ul>
 *
 * <h2>Why not UserDetails</h2>
 * Spring Security's {@code UserDetails} exists to support
 * {@code UserDetailsService}-based flows. This platform uses stateless
 * JWT/API key auth — {@code AuthenticationManager} is never invoked.
 * The full interface would add five no-op methods with no callers.
 */
public record UserPrincipal(

        UUID userId,

        String tenantId,

        String email,

        List<String> roles,

        /**
         * UUIDs of teams the user belongs to.
         * Populated from JWT {@code teamIds} claim. Empty for API key principals
         * (team membership not relevant for machine-to-machine calls).
         */
        List<UUID> teamIds,

        /**
         * True when this principal was authenticated via an API key.
         * False for JWT-authenticated requests.
         *
         * <p>Used by service layer to apply scope-based authorization in
         * addition to role-based authorization.
         */
        boolean isApiKey,

        /**
         * Granted API key scopes — only populated when {@link #isApiKey} is true.
         * Empty list for JWT-authenticated principals.
         *
         * <p>Example values: {@code "incidents:read"}, {@code "alerts:ingest"}.
         * Checked via {@link #hasScope(String)} in controller/service layer.
         */
        List<String> scopes

) {

    public UserPrincipal {
        roles   = roles   != null ? List.copyOf(roles)   : List.of();
        teamIds = teamIds != null ? List.copyOf(teamIds) : List.of();
        scopes  = scopes  != null ? List.copyOf(scopes)  : List.of();
    }

    /**
     * Convenience constructor for JWT-authenticated principals.
     * Sets {@link #isApiKey} = false and {@link #scopes} = empty.
     */
    public UserPrincipal(UUID userId, String tenantId, String email,
                         List<String> roles, List<UUID> teamIds) {
        this(userId, tenantId, email, roles, teamIds, false, List.of());
    }

    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean isMemberOf(UUID teamId) {
        return teamIds.contains(teamId);
    }

    /**
     * Returns true if this principal (when authenticated via API key)
     * has been granted the specified scope.
     *
     * <p>For JWT principals ({@link #isApiKey} = false), scope checks are
     * not applicable — use role checks instead.
     */
    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}