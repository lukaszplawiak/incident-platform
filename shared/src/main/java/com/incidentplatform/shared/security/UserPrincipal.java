package com.incidentplatform.shared.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Authenticated principal built directly from JWT claims by
 * {@link JwtAuthFilter} and set on
 * {@link org.springframework.security.authentication.UsernamePasswordAuthenticationToken}.
 *
 * <h2>Why this is not a {@code UserDetails}</h2>
 * Spring Security's {@code UserDetails} interface ({@code isAccountNonExpired()},
 * {@code isAccountNonLocked()}, {@code isCredentialsNonExpired()},
 * {@code isEnabled()}, {@code getPassword()}, {@code getUsername()}) exists to
 * support {@code UserDetailsService}-based authentication flows, where Spring
 * Security loads a user from a store and checks these flags before granting
 * access.
 *
 * <p>This platform uses stateless JWT authentication: {@link JwtAuthFilter}
 * extracts claims from an already-validated token and constructs the
 * {@code Authentication} object directly —
 * <pre>{@code
 * new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
 * }</pre>
 * There is no {@code AuthenticationManager} or {@code UserDetailsService} in
 * this architecture, so Spring Security never calls any {@code UserDetails}
 * method other than {@code getAuthorities()}. Implementing the full interface
 * added five no-op methods that always returned {@code true} or {@code null}
 * with no caller — unnecessary complexity for behaviour that is never invoked.
 */
public record UserPrincipal(

        UUID userId,

        String tenantId,

        String email,

        List<String> roles,

        /**
         * UUIDs of teams the user belongs to.
         * Populated from the {@code teamIds} JWT claim on login.
         * Empty list means the user has no team assignments.
         *
         * <p>Note: team-level roles (MANAGER/RESPONDER) are NOT included
         * here — only team membership. Team-level role checks require
         * a server-side lookup via TeamMemberRepository.
         */
        List<UUID> teamIds

) {

    public UserPrincipal {
        roles   = roles   != null ? List.copyOf(roles)   : List.of();
        teamIds = teamIds != null ? List.copyOf(teamIds) : List.of();
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
}