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

    /** Security notification (backlog #0-83): the user disabled MFA on the account. No token. */
    MFA_DISABLED,

    /**
     * Security notification (backlog #0-88): an administrator reset the
     * account's MFA (the admin reset or the break-glass command). Its own
     * type, so the user can tell it from disabling MFA themselves and notice
     * a reset they did not ask for. No token.
     */
    MFA_RESET,

    /**
     * Security notification (backlog #0-89): an API key was created with the
     * account (a personal key to its owner, a tenant key to the admin who
     * created it), so a key the owner did not create is noticed. No token.
     */
    API_KEY_CREATED;

    /**
     * Whether a newer request of this type makes an unsent older one
     * pointless, so the scheduler closes the older one as SUPERSEDED. True for
     * every type but {@link #API_KEY_CREATED} (backlog #0-89, review): each of
     * those is about a different key, and dropping one would let a key made
     * right after another go unannounced.
     */
    public boolean supersededByNewer() {
        return this != API_KEY_CREATED;
    }

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
            case MFA_ENABLED, MFA_DISABLED, MFA_RESET, API_KEY_CREATED ->
                    throw new IllegalStateException(this + " is a notification and carries no token");
        };
    }
}