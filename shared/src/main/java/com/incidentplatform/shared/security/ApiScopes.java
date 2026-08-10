package com.incidentplatform.shared.security;

/**
 * String constants for API key scope names that are checked outside
 * auth-service (via {@link UserPrincipal#hasScope(String)}, typically in
 * a {@code @PreAuthorize} SpEL expression).
 *
 * <h2>Why this exists</h2>
 * auth-service's {@code ApiKeyScope} enum is where scope names are
 * actually granted and where the full scope catalog lives — but that
 * enum is defined in auth-service's domain package, which other services
 * don't (and shouldn't) depend on: pulling in the whole enum, including
 * auth-service's own role-eligibility logic
 * ({@code ApiKeyScope.allowedForRole}), would be a much heavier and
 * inappropriate coupling for a consuming service that just needs to
 * check one scope name string.
 *
 * <p>Before this class existed, {@code ingestion-service}'s
 * {@code AlertIngestionController} checked
 * {@code principal.hasScope('alerts:ingest')} as a bare string literal,
 * duplicating (not referencing) the value auth-service's
 * {@code ApiKeyScope.ALERTS_INGEST} independently defines. Nothing
 * detected a rename or typo in either place — a mismatch would silently
 * break API key authorization for alert ingestion, discovered only in
 * production when a real integration's key stopped working.
 *
 * <p>Now both sides reference this one constant: auth-service's
 * {@code ApiKeyScope.ALERTS_INGEST} enum value is constructed from it,
 * and ingestion-service's {@code @PreAuthorize} references it via SpEL's
 * {@code T(com.incidentplatform.shared.security.ApiScopes).ALERTS_INGEST}
 * syntax. A rename here is a single edit that automatically propagates to
 * both — and if the two ever did drift apart, it could now only happen by
 * someone explicitly typing a literal string again, not by silent
 * independent evolution.
 *
 * <h2>Scope</h2>
 * Deliberately holds only the one scope name that actually crosses a
 * service boundary this way today (verified by searching for
 * {@code hasScope(} usage across every service — {@code alerts:ingest}
 * is the only one). auth-service's other scopes (
 * {@code incidents:read/write}, {@code postmortems:read/write},
 * {@code oncall:read}, {@code teams:read/write}) are all currently
 * checked entirely within auth-service itself. Add a constant here only
 * when another service needs to check a specific scope by name — this
 * class is not meant to become a full mirror of {@code ApiKeyScope}.
 */
public final class ApiScopes {

    public static final String ALERTS_INGEST = "alerts:ingest";

    private ApiScopes() {}
}