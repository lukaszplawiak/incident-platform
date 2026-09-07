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
         * UUIDs of teams where the user holds {@code TeamRole.MANAGER}
         * (auth-service's domain — this module doesn't depend on
         * auth-service, so the role name itself isn't referenced here,
         * just its effect: which teams this user manages).
         *
         * <p>Populated from JWT {@code managedTeamIds} claim — a subset of
         * {@link #teamIds}. Empty for API key principals, same reasoning
         * as {@link #teamIds}.
         *
         * <p>Added for the Manager role feature: a Manager needs full
         * access to create/update/delete on-call schedules and manage
         * membership for teams they manage, without needing the
         * tenant-wide {@code ROLE_ADMIN} role — see {@link #isManagerOf}.
         */
        List<UUID> managedTeamIds,

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
        List<String> scopes,

        /**
         * Links this principal's access token to the {@code AuthToken}
         * (REFRESH type, auth-service) issued at the same login — see
         * auth-service migration V16's own comment for the full account
         * of why this exists. Populated from the JWT {@code sessionId}
         * claim by {@link JwtAuthFilter}; null for API key principals
         * (no login session — machine-to-machine authentication) and for
         * any access token issued with no session (service tokens,
         * dev/test tokens via {@code DevTokenController}, or tokens
         * issued before this claim existed).
         */
        UUID sessionId

) {

    public UserPrincipal {
        roles          = roles          != null ? List.copyOf(roles)          : List.of();
        teamIds        = teamIds        != null ? List.copyOf(teamIds)        : List.of();
        managedTeamIds = managedTeamIds != null ? List.copyOf(managedTeamIds) : List.of();
        scopes         = scopes         != null ? List.copyOf(scopes)         : List.of();
    }

    /**
     * Convenience constructor for JWT-authenticated principals with no
     * associated session — {@link #sessionId} = null. See the
     * session-aware overload below when a real session is available.
     * Sets {@link #isApiKey} = false and {@link #scopes} = empty.
     */
    public UserPrincipal(UUID userId, String tenantId, String email,
                         List<String> roles, List<UUID> teamIds) {
        this(userId, tenantId, email, roles, teamIds, List.of(), false, List.of(), null);
    }

    /**
     * Convenience constructor for JWT-authenticated principals that also
     * carries {@link #managedTeamIds}, with no associated session —
     * {@link #sessionId} = null. See the session-aware overload below
     * when a real session is available.
     */
    public UserPrincipal(UUID userId, String tenantId, String email,
                         List<String> roles, List<UUID> teamIds,
                         List<UUID> managedTeamIds) {
        this(userId, tenantId, email, roles, teamIds, managedTeamIds, false, List.of(), null);
    }

    /**
     * Convenience constructor for JWT-authenticated principals built from
     * a real login session — used by {@link JwtAuthFilter} once it has
     * extracted a non-empty {@code sessionId} claim. Every other
     * convenience constructor in this class defaults {@link #sessionId}
     * to null; this is the one path that carries a real value through.
     */
    public UserPrincipal(UUID userId, String tenantId, String email,
                         List<String> roles, List<UUID> teamIds,
                         List<UUID> managedTeamIds, UUID sessionId) {
        this(userId, tenantId, email, roles, teamIds, managedTeamIds,
                false, List.of(), sessionId);
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
     * Returns true if this principal holds {@code TeamRole.MANAGER} for
     * the given team. {@code teamId == null} always returns false — a
     * tenant-wide (no-team) resource is not something any team Manager
     * has authority over; only {@code ROLE_ADMIN} does.
     *
     * <p>Used by services in oncall-service (on-call schedule
     * create/delete) and auth-service (team membership management) to
     * grant Managers full access to their own team's resources without
     * needing tenant-wide {@code ROLE_ADMIN}.
     */
    public boolean isManagerOf(UUID teamId) {
        return teamId != null && managedTeamIds.contains(teamId);
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