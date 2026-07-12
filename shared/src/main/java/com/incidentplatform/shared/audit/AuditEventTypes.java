package com.incidentplatform.shared.audit;

/**
 * Audit event type string constants used in {@link AuditEventPublisher} calls.
 *
 * <p>Centralised in {@code shared} so all services use the same values.
 * These values form part of the audit trail contract — they appear in
 * {@code AuditEventMessage.eventType()} published to Kafka and stored
 * for compliance. Treat them as stable identifiers; rename with caution.
 */
public final class AuditEventTypes {

    // ── Incident lifecycle ──────────────────────────────────────────────────
    public static final String INCIDENT_CREATED          = "INCIDENT_CREATED";
    public static final String INCIDENT_ACKNOWLEDGED     = "INCIDENT_ACKNOWLEDGED";
    public static final String INCIDENT_RESOLVED         = "INCIDENT_RESOLVED";
    public static final String INCIDENT_CLOSED           = "INCIDENT_CLOSED";
    public static final String INCIDENT_ESCALATED        = "INCIDENT_ESCALATED";
    public static final String INCIDENT_STATUS_CHANGED   = "INCIDENT_STATUS_CHANGED";
    public static final String INCIDENT_ASSIGNED         = "INCIDENT_ASSIGNED";
    public static final String INCIDENT_SEVERITY_UPDATED = "INCIDENT_SEVERITY_UPDATED";
    public static final String INCIDENT_OPENED           = "INCIDENT_OPENED";

    // ── Escalation ──────────────────────────────────────────────────────────
    public static final String ESCALATION_FIRED          = "ESCALATION_FIRED";
    public static final String ESCALATION_SCHEDULED      = "ESCALATION_SCHEDULED";

    // ── Notification ────────────────────────────────────────────────────────
    public static final String NOTIFICATION_SENT         = "NOTIFICATION_SENT";
    public static final String NOTIFICATION_FAILED       = "NOTIFICATION_FAILED";

    // ── Postmortem ──────────────────────────────────────────────────────────
    public static final String POSTMORTEM_GENERATED      = "POSTMORTEM_GENERATED";
    public static final String POSTMORTEM_FAILED         = "POSTMORTEM_FAILED";
    public static final String POSTMORTEM_PERMANENTLY_FAILED = "POSTMORTEM_PERMANENTLY_FAILED";
    public static final String POSTMORTEM_UPDATED        = "POSTMORTEM_UPDATED";


    // ── Auth ────────────────────────────────────────────────────────────────
    public static final String USER_LOGIN              = "USER_LOGIN";
    public static final String USER_LOGOUT             = "USER_LOGOUT";
    public static final String USER_LOGIN_FAILED       = "USER_LOGIN_FAILED";
    public static final String USER_CREATED            = "USER_CREATED";
    public static final String USER_DELETED            = "USER_DELETED";
    public static final String USER_ROLES_UPDATED      = "USER_ROLES_UPDATED";
    public static final String USER_STATUS_UPDATED     = "USER_STATUS_UPDATED";
    public static final String USER_PASSWORD_CHANGED   = "USER_PASSWORD_CHANGED";
    public static final String USER_PASSWORD_RESET     = "USER_PASSWORD_RESET";
    public static final String USER_PASSWORD_RESET_REQUESTED = "USER_PASSWORD_RESET_REQUESTED";
    public static final String USER_INVITE_SENT        = "USER_INVITE_SENT";
    public static final String USER_INVITE_ACCEPTED    = "USER_INVITE_ACCEPTED";
    public static final String USER_INVITE_RESENT      = "USER_INVITE_RESENT";

    private AuditEventTypes() {}
}