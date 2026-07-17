package com.incidentplatform.auth.domain;

/**
 * Granular permissions that can be granted to an {@link ApiKey}.
 *
 * <h2>Design principles</h2>
 * <ul>
 *   <li>Scopes follow the {@code resource:action} convention (GitHub model).</li>
 *   <li>Read scopes are distinct from write scopes — a monitoring dashboard
 *       needs {@code INCIDENTS_READ} but never {@code INCIDENTS_WRITE}.</li>
 *   <li>Personal API keys cannot be granted scopes beyond what the owner's
 *       tenant-level role permits — an {@code ROLE_RESPONDER} owner cannot
 *       create a key with {@code TEAMS_WRITE}.</li>
 * </ul>
 *
 * <h2>Adding a new scope</h2>
 * <ol>
 *   <li>Add the enum value here.</li>
 *   <li>Update {@link #allowedForRole} if the scope should be restricted.</li>
 *   <li>Add the corresponding {@code hasScope()} check in the service layer.</li>
 * </ol>
 *
 * <h2>Stored as</h2>
 * Scope names ({@link #scopeName}) are stored in the {@code api_keys.scopes}
 * PostgreSQL TEXT ARRAY. The {@code @} operator checks containment.
 */
public enum ApiKeyScope {

    // ── Incidents ─────────────────────────────────────────────────────────

    /** Read incidents, list, filter, get by id. Available to RESPONDER+. */
    INCIDENTS_READ("incidents:read"),

    /** Create, update, resolve incidents. Available to RESPONDER+. */
    INCIDENTS_WRITE("incidents:write"),

    // ── Alerts ────────────────────────────────────────────────────────────

    /** Ingest alerts via POST /api/v1/alerts (ingestion-service). Available to RESPONDER+. */
    ALERTS_INGEST("alerts:ingest"),

    // ── Postmortems ───────────────────────────────────────────────────────

    /** Read postmortems. Available to RESPONDER+. */
    POSTMORTEMS_READ("postmortems:read"),

    /** Create and update postmortems. Available to RESPONDER+. */
    POSTMORTEMS_WRITE("postmortems:write"),

    // ── On-call ───────────────────────────────────────────────────────────

    /** Read on-call schedules and current on-call user. Available to RESPONDER+. */
    ONCALL_READ("oncall:read"),

    // ── Teams ─────────────────────────────────────────────────────────────

    /** Read team membership. Available to RESPONDER+. */
    TEAMS_READ("teams:read"),

    /** Manage teams and membership. ADMIN only. */
    TEAMS_WRITE("teams:write");

    private final String scopeName;

    ApiKeyScope(String scopeName) {
        this.scopeName = scopeName;
    }

    public String getScopeName() {
        return scopeName;
    }

    /**
     * Returns the scope from its string name (e.g. {@code "incidents:read"}).
     *
     * @throws IllegalArgumentException if no matching scope exists
     */
    public static ApiKeyScope fromScopeName(String scopeName) {
        for (final ApiKeyScope scope : values()) {
            if (scope.scopeName.equals(scopeName)) {
                return scope;
            }
        }
        throw new IllegalArgumentException(
                "Unknown API key scope: '" + scopeName + "'. " +
                        "Valid scopes: incidents:read, incidents:write, alerts:ingest, " +
                        "postmortems:read, postmortems:write, oncall:read, teams:read, teams:write");
    }

    /**
     * Returns true if this scope can be granted to a user with the given role.
     *
     * <p>Used to enforce that PERSONAL keys cannot exceed owner's permissions:
     * a {@code ROLE_RESPONDER} owner cannot create a key with
     * {@link #TEAMS_WRITE} (ADMIN-only scope).
     */
    public boolean allowedForRole(String role) {
        return switch (this) {
            case TEAMS_WRITE -> "ROLE_ADMIN".equals(role);
            // All other scopes available to RESPONDER and above
            default -> "ROLE_ADMIN".equals(role) || "ROLE_RESPONDER".equals(role);
        };
    }

    @Override
    public String toString() {
        return scopeName;
    }
}