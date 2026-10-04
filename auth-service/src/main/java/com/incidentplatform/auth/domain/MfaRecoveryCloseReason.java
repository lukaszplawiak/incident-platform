package com.incidentplatform.auth.domain;

/** Why an {@link MfaRecoveryRequest} ended without a reset (backlog #0-90). */
public enum MfaRecoveryCloseReason {
    /** The account used the cancel link of its notice. */
    CANCELLED_BY_ACCOUNT,
    /** A platform operator cancelled it. */
    CANCELLED_BY_OPERATOR,
    /**
     * When its time came the tenant had another active admin, who can reset
     * the factor themselves (backlog #0-88), so the platform does not.
     */
    OTHER_ADMIN_EXISTS,
    /**
     * When its time came the user was no longer an active admin with MFA
     * (MFA disabled, role removed, deactivated, archived).
     */
    NO_LONGER_APPLICABLE,
    /**
     * When its time came the operator who asked was no longer an active admin
     * of the operator tenant (deactivated, demoted, archived): a request from
     * an account later found compromised does not outlive it (review).
     */
    OPERATOR_NO_LONGER_ADMIN,
    /** Its notice was never sent within the notice deadline. */
    NOTICE_NOT_DELIVERED
}
