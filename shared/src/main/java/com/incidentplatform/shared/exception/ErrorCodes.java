package com.incidentplatform.shared.exception;

/**
 * Error code string constants used in {@link com.incidentplatform.shared.dto.ErrorResponse}
 * and {@link BusinessException} factory methods.
 *
 * <p>These codes form part of the API contract — clients may use them for
 * programmatic error handling. Treat them as stable identifiers;
 * rename only with a coordinated API version change.
 *
 * <p>Centralised here so all services use consistent codes and a typo
 * in one place doesn't silently produce a different error code in the response.
 */
public final class ErrorCodes {

    // ── HTTP / infrastructure ───────────────────────────────────────────────
    public static final String RESOURCE_NOT_FOUND       = "RESOURCE_NOT_FOUND";
    public static final String VALIDATION_FAILED        = "VALIDATION_FAILED";
    public static final String INVALID_PARAMETER_TYPE   = "INVALID_PARAMETER_TYPE";
    public static final String UNAUTHORIZED             = "UNAUTHORIZED";
    /** Backlog #0-16: the credential could not be checked right now; retry (503). */
    public static final String AUTHENTICATION_UNAVAILABLE = "AUTHENTICATION_UNAVAILABLE";
    /** Backlog #0-16: too many failed API key authentications from this client (429). */
    public static final String TOO_MANY_FAILED_AUTHENTICATIONS = "TOO_MANY_FAILED_AUTHENTICATIONS";
    public static final String FORBIDDEN                = "FORBIDDEN";
    public static final String OPTIMISTIC_LOCK_CONFLICT = "OPTIMISTIC_LOCK_CONFLICT";
    public static final String INTERNAL_SERVER_ERROR    = "INTERNAL_SERVER_ERROR";

    // ── Incident domain ─────────────────────────────────────────────────────
    public static final String INCIDENT_ALREADY_CLOSED    = "INCIDENT_ALREADY_CLOSED";
    public static final String INVALID_STATUS_TRANSITION  = "INVALID_STATUS_TRANSITION";

    // ── Oncall domain ────────────────────────────────────────────────────────
    public static final String ON_CALL_NOT_CONFIGURED = "ON_CALL_NOT_CONFIGURED";
    public static final String SCHEDULE_OVERLAP       = "SCHEDULE_OVERLAP";

    // ── Auth domain ──────────────────────────────────────────────────────────
    public static final String EMAIL_ALREADY_EXISTS  = "EMAIL_ALREADY_EXISTS";
    public static final String INVALID_TOKEN         = "INVALID_TOKEN";
    public static final String BUSINESS_RULE_VIOLATION = "BUSINESS_RULE_VIOLATION";
    public static final String ALREADY_EXISTS = "ALREADY_EXISTS";
    /** Backlog #0-82: the tenant is suspended in full; every request of its users and keys is refused. */
    public static final String TENANT_SUSPENDED = "TENANT_SUSPENDED";
    /** Backlog #0-82: the tenant is suspended to read-only; a write is refused. */
    public static final String TENANT_READ_ONLY = "TENANT_READ_ONLY";

    // ── Ingestion domain ─────────────────────────────────────────────────────
    public static final String NORMALIZATION_FAILED  = "NORMALIZATION_FAILED";
    public static final String UNKNOWN_ALERT_SOURCE  = "UNKNOWN_ALERT_SOURCE";
    /** Backlog #0-96: an alert that could not be processed could not be kept either; retry (503). */
    public static final String INGESTION_UNAVAILABLE = "INGESTION_UNAVAILABLE";

    private ErrorCodes() {}
}