package com.incidentplatform.shared.security;

/**
 * Strategy interface for checking whether a JWT has been revoked.
 *
 * <h2>Why a dedicated interface instead of {@code Predicate<String>}</h2>
 * {@code Predicate<String>} would work mechanically but carries no domain
 * meaning — a reader seeing {@code Predicate<String>} in a constructor cannot
 * tell whether it checks revocation, validates format, or does something else
 * entirely. {@code TokenRevocationChecker} communicates intent immediately.
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@code TokenRevocationService} in {@code auth-service} — Redis-backed,
 *       checks {@code auth:revoked:{jti}} on every authenticated request.</li>
 *   <li>No-op lambda {@code jti -> false} in
 *       {@link SharedSecurityAutoConfiguration} — used by all other services
 *       that do not need revocation checking.</li>
 * </ul>
 *
 * <h2>Placing this interface in {@code shared}</h2>
 * {@code JwtAuthFilter} lives in {@code shared} and needs to call this
 * interface. {@code TokenRevocationService} lives in {@code auth-service}.
 * The interface lives in {@code shared} so that {@code JwtAuthFilter} can
 * depend on the abstraction without creating a circular module dependency.
 * {@code auth-service} implements the interface; {@code shared} never
 * knows about {@code auth-service}.
 */
@FunctionalInterface
public interface TokenRevocationChecker {

    /**
     * Returns {@code true} if the token identified by the given JWT ID
     * has been revoked and should be rejected.
     *
     * <p>Implementations must be fail-open: if the revocation store
     * (e.g. Redis) is unavailable, return {@code false} rather than
     * throwing — a store outage must not lock out all users.
     *
     * @param jti the JWT ID claim ({@code jti}) from the token being validated
     * @return {@code true} if revoked, {@code false} if valid or store unavailable
     */
    boolean isRevoked(String jti);
}