package com.incidentplatform.auth.domain;

/**
 * Discriminator for {@link AuthEmailOutbox} — determines which email
 * template and link are used when the scheduler processes an entry.
 */
public enum AuthEmailType {

    /**
     * New user onboarding — sent after admin creates a user account.
     * Link: {@code {appBaseUrl}/accept-invite?token={rawToken}}
     * TTL: 7 days from when the email is sent (backlog #0-52).
     */
    INVITE,

    /**
     * Self-service password recovery — sent after user requests a reset.
     * Link: {@code {appBaseUrl}/reset-password?token={rawToken}}
     * TTL: 15 minutes from when the email is sent (backlog #0-52).
     */
    PASSWORD_RESET;

    /** The token type an email of this type carries. */
    public AuthToken.Type tokenType() {
        return switch (this) {
            case INVITE -> AuthToken.Type.INVITE;
            case PASSWORD_RESET -> AuthToken.Type.PASSWORD_RESET;
        };
    }
}