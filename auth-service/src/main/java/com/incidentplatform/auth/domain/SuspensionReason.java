package com.incidentplatform.auth.domain;

/** Why a tenant was suspended (backlog #0-82); the operator's note says the rest. */
public enum SuspensionReason {
    /** The tenant's accounts are, or may be, in someone else's hands. */
    SECURITY,
    /** Unpaid or disputed billing. */
    BILLING,
    /** A breach of the terms of service. */
    TERMS,
    /** Anything else; the note must say what. */
    OTHER
}
