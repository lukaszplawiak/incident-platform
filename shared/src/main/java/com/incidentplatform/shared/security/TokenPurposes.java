package com.incidentplatform.shared.security;

/**
 * Values of the {@code purpose} claim of a purpose-scoped service token
 * (see {@link JwtUtils#generatePurposeToken}).
 *
 * <h2>Added (backlog #0-16): a service token that acts for no tenant</h2>
 * Every other service token acts for one tenant, carried in its signed
 * {@code tenantId} claim, and {@link JwtAuthFilter} rejects a service token
 * without one. A few calls cannot know the tenant before they are made:
 * ingestion-service asks auth-service which tenant an Integration API key
 * belongs to, so the tenant is the <em>answer</em>, not an input. Rather than
 * minting that call's token for a fake or reserved tenant (which would make
 * the fake tenant valid on every tenant-scoped endpoint of auth-service), the
 * token names the single operation it is for. {@link JwtAuthFilter} accepts a
 * purpose only in a service that lists it, turns it into an
 * {@link IntrospectionPrincipal} with no tenant, and every route except the
 * one that needs it denies that principal
 * ({@link SharedSecurityAutoConfiguration#authenticatedExceptPurposeTokens}).
 */
public final class TokenPurposes {

    /**
     * Resolve an Integration API key hash to its tenant, team and scopes.
     * Accepted only by auth-service, only on
     * {@code POST /api/v1/internal/api-keys/introspect}.
     */
    public static final String API_KEY_INTROSPECTION = "api-key-introspection";

    private TokenPurposes() {
    }
}
