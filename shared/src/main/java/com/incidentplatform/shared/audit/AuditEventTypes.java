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
    public static final String ESCALATION_NOTIFICATION_FAILED = "ESCALATION_NOTIFICATION_FAILED";

    // ── Notification ────────────────────────────────────────────────────────
    public static final String NOTIFICATION_SENT         = "NOTIFICATION_SENT";
    public static final String NOTIFICATION_FAILED       = "NOTIFICATION_FAILED";
    /**
     * Nobody in the tenant could be notified (backlog #0-18): nobody on call, no
     * on-call contact with an address on any enabled channel, or oncall-service
     * unavailable past the retry window. The incident text was deliberately not
     * sent anywhere. Distinct from {@link #NOTIFICATION_FAILED}, which means a
     * send through one channel failed: an auditor filtering by type must not get
     * both meanings.
     */
    public static final String NOTIFICATION_UNDELIVERABLE = "NOTIFICATION_UNDELIVERABLE";
    public static final String SLACK_ACK_MESSAGE_UPDATE_FAILED = "SLACK_ACK_MESSAGE_UPDATE_FAILED";

    // ── Postmortem ──────────────────────────────────────────────────────────
    public static final String POSTMORTEM_GENERATED      = "POSTMORTEM_GENERATED";
    public static final String POSTMORTEM_FAILED         = "POSTMORTEM_FAILED";
    public static final String POSTMORTEM_PERMANENTLY_FAILED = "POSTMORTEM_PERMANENTLY_FAILED";
    public static final String POSTMORTEM_UPDATED        = "POSTMORTEM_UPDATED";
    public static final String POSTMORTEM_REVIEWED       = "POSTMORTEM_REVIEWED";


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
    public static final String USER_ARCHIVED           = "USER_ARCHIVED";
    public static final String USER_RESTORED           = "USER_RESTORED";
    public static final String USER_ANONYMIZED         = "USER_ANONYMIZED";

    // ── Platform (backlog #0-80) ─────────────────────────────────────────────
    // Recorded in the platform-operator tenant: an operator's action on another
    // tenant. The tenant's own audit trail gets the USER_CREATED / USER_INVITE_*
    // events of the same action.
    public static final String TENANT_PROVISIONED      = "TENANT_PROVISIONED";
    public static final String TENANT_ADMIN_REINVITED  = "TENANT_ADMIN_REINVITED";


    // ── Teams ────────────────────────────────────────────────────────────────
    public static final String TEAM_CREATED             = "TEAM_CREATED";
    public static final String TEAM_ARCHIVED            = "TEAM_ARCHIVED";
    public static final String TEAM_RESTORED            = "TEAM_RESTORED";
    public static final String TEAM_DELETED             = "TEAM_DELETED";
    public static final String TEAM_MEMBER_ADDED        = "TEAM_MEMBER_ADDED";
    public static final String TEAM_MEMBER_REMOVED      = "TEAM_MEMBER_REMOVED";
    public static final String TEAM_MEMBER_ROLE_UPDATED = "TEAM_MEMBER_ROLE_UPDATED";
    public static final String INCIDENT_TEAM_ASSIGNED   = "INCIDENT_TEAM_ASSIGNED";
    public static final String INCIDENT_TEAM_UNASSIGNED = "INCIDENT_TEAM_UNASSIGNED";

    // ── MFA ─────────────────────────────────────────────────────────────────
    public static final String MFA_ENABLED                = "MFA_ENABLED";
    public static final String MFA_DISABLED               = "MFA_DISABLED";
    /**
     * An admin of the user's tenant removed the user's second factor (backlog
     * #0-88): the factor, its backup codes and every session of the user. The
     * actor is the admin. Distinct from {@link #MFA_DISABLED}, which means the
     * user turned MFA off with their own password and code: an auditor
     * filtering by type must not get both meanings.
     */
    public static final String MFA_RESET_BY_ADMIN         = "MFA_RESET_BY_ADMIN";
    /**
     * The break-glass form of {@link #MFA_RESET_BY_ADMIN} (backlog #0-88): run
     * from the command line by whoever operates the deployment, for a
     * platform operator with no other operator admin to reset them. The actor
     * is the name given on the command line, with the reason in the metadata.
     * Its own type, so every use of break-glass can be found by type.
     */
    public static final String MFA_RESET_BREAK_GLASS      = "MFA_RESET_BREAK_GLASS";
    /**
     * A platform operator asked to reset the second factor of a customer
     * tenant's only admin (backlog #0-90), after verifying the person outside
     * the account's own channels. Recorded in both tenants: the operator
     * tenant's event carries the operator's verification note, the customer
     * tenant's only the method. Nothing changes yet: the reset waits until the
     * account has been told for the waiting period, and the account can cancel it.
     */
    public static final String MFA_RECOVERY_REQUESTED     = "MFA_RECOVERY_REQUESTED";
    /**
     * A {@link #MFA_RECOVERY_REQUESTED} request ended without a reset: cancelled
     * by the account (the link in its notice), by an operator, or when the
     * reset no longer applied at its time (another admin appeared, the factor
     * was already gone). The reason is in the metadata. Both tenants.
     */
    public static final String MFA_RECOVERY_CANCELLED     = "MFA_RECOVERY_CANCELLED";
    /**
     * The waiting period of a {@link #MFA_RECOVERY_REQUESTED} request ended
     * uncancelled and the platform reset the admin's factor, password and
     * sessions (backlog #0-90). Written by the platform, not a person. Both tenants.
     */
    public static final String MFA_RECOVERY_EXECUTED      = "MFA_RECOVERY_EXECUTED";
    /**
     * A {@link #MFA_RECOVERY_REQUESTED} request was given up because its notice
     * never reached the account: a reset the account was never told about must
     * not happen. Both tenants.
     */
    public static final String MFA_RECOVERY_EXPIRED       = "MFA_RECOVERY_EXPIRED";
    public static final String MFA_VERIFY_SUCCESS         = "MFA_VERIFY_SUCCESS";
    public static final String MFA_VERIFY_FAILED          = "MFA_VERIFY_FAILED";
    public static final String MFA_BACKUP_CODE_USED       = "MFA_BACKUP_CODE_USED";
    public static final String TENANT_MFA_POLICY_UPDATED  = "TENANT_MFA_POLICY_UPDATED";

    // ── API Keys ──────────────────────────────────────────────────────────────
    public static final String API_KEY_CREATED = "API_KEY_CREATED";
    public static final String API_KEY_REVOKED = "API_KEY_REVOKED";
    public static final String API_KEY_USED    = "API_KEY_USED";

    // ── Integrations ──────────────────────────────────────────────────────────
    public static final String INTEGRATION_CREATED = "INTEGRATION_CREATED";
    public static final String INTEGRATION_REVOKED = "INTEGRATION_REVOKED";

    // ── Slack workspace (backlog #0-21) ─────────────────────────────────────
    public static final String SLACK_WORKSPACE_INSTALLED = "SLACK_WORKSPACE_INSTALLED";
    public static final String SLACK_WORKSPACE_REVOKED   = "SLACK_WORKSPACE_REVOKED";

    private AuditEventTypes() {}
}