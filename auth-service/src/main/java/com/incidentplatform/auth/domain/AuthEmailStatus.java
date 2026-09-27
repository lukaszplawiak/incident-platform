package com.incidentplatform.auth.domain;

/**
 * Processing status of an {@link AuthEmailOutbox} entry. Only
 * {@code AuthEmailScheduler} moves an entry out of PENDING (backlog #0-52).
 */
public enum AuthEmailStatus {

    /** Requested, not attempted yet. */
    PENDING,

    /** Sent: the SMTP server accepted it. */
    SENT,

    /**
     * The last send failed; retried at {@code next_attempt_at}, with backoff,
     * until the entry's deadline (backlog #0-52).
     */
    FAILED,

    /**
     * Never sent: the deadline passed while sends kept failing, or before the
     * entry could be tried. Counted in {@code auth.email.permanently_failed}.
     * An admin resends the invite; a user requests a new password reset.
     */
    PERMANENTLY_FAILED,

    /**
     * No longer needed (backlog #0-52): a newer request of the same type for
     * the same user exists, the invite was already accepted, or the user is
     * gone. Not a failure, so not alerted on.
     */
    SUPERSEDED
}
