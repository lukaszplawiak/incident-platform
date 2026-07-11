package com.incidentplatform.auth.domain;

/**
 * Discriminator for {@link AuthEmailOutbox} — determines which email
 * template and link are used when the scheduler processes an entry.
 */
public enum AuthEmailType {

    /**
     * New user onboarding — sent after admin creates a user account.
     * Link: {@code {appBaseUrl}/accept-invite?token={rawToken}}
     * TTL: 7 days.
     */
    INVITE,

    /**
     * Self-service password recovery — sent after user requests a reset.
     * Link: {@code {appBaseUrl}/reset-password?token={rawToken}}
     * TTL: 15 minutes.
     */
    PASSWORD_RESET
}