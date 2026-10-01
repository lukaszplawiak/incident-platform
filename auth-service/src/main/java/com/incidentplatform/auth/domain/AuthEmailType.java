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
    PASSWORD_RESET,

    /**
     * Security notification (backlog #0-83): MFA was enabled on the account.
     * No token, no link. Tells the real owner if someone else enrolled a
     * factor with their password; the platform API's grace period
     * ({@code platform.mfa.enrolment-grace}) gives them time to react.
     */
    MFA_ENABLED,

    /** Security notification (backlog #0-83): MFA was disabled on the account. No token. */
    MFA_DISABLED;

    /** Whether an email of this type carries a token (a link to act on). */
    public boolean carriesToken() {
        return this == INVITE || this == PASSWORD_RESET;
    }

    /**
     * The token type an email of this type carries.
     *
     * @throws IllegalStateException for a notification, which carries none
     */
    public AuthToken.Type tokenType() {
        return switch (this) {
            case INVITE -> AuthToken.Type.INVITE;
            case PASSWORD_RESET -> AuthToken.Type.PASSWORD_RESET;
            case MFA_ENABLED, MFA_DISABLED ->
                    throw new IllegalStateException(this + " is a notification and carries no token");
        };
    }
}