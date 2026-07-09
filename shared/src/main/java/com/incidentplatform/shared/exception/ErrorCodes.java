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

    // ── Ingestion domain ─────────────────────────────────────────────────────
    public static final String NORMALIZATION_FAILED  = "NORMALIZATION_FAILED";
    public static final String UNKNOWN_ALERT_SOURCE  = "UNKNOWN_ALERT_SOURCE";

    private ErrorCodes() {}
}