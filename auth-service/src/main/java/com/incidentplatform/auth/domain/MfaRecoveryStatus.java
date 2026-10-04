package com.incidentplatform.auth.domain;

/**
 * Status of an {@link MfaRecoveryRequest} (backlog #0-90). PENDING is the only
 * open one; every other is final, reached by a conditional UPDATE from PENDING.
 */
public enum MfaRecoveryStatus {
    /** Waiting for its notice to be sent, then for the waiting period. */
    PENDING,
    /** The admin's factor, password and sessions were reset. */
    EXECUTED,
    /** Ended without a reset; {@link MfaRecoveryCloseReason} says why. */
    CANCELLED,
    /** Its notice never reached the account, so it never ran. */
    EXPIRED
}
