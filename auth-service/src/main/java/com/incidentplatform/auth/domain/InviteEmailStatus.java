package com.incidentplatform.auth.domain;

/**
 * Processing status of an {@link InviteEmailOutbox} entry.
 */
public enum InviteEmailStatus {

    /** Written by UserService — awaiting processing by InviteEmailScheduler. */
    PENDING,

    /** Email sent successfully. raw_token has been NULLed. */
    SENT,

    /**
     * Sending failed — will be retried by the scheduler up to
     * {@code invite.email.max-retry-attempts} times.
     */
    FAILED,

    /**
     * All retry attempts exhausted — email was never sent.
     * Admin must use the resend-invite endpoint to restart the flow.
     */
    PERMANENTLY_FAILED
}