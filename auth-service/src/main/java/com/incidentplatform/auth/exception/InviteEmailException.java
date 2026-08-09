package com.incidentplatform.auth.exception;

/**
 * Thrown by {@link com.incidentplatform.auth.service.AuthEmailService} when
 * sending an invite or password reset email fails.
 * Caught by {@link com.incidentplatform.auth.scheduler.AuthEmailScheduler}
 * which marks the outbox entry FAILED (with remaining retry budget) or
 * PERMANENTLY_FAILED (retries exhausted) via
 * {@link com.incidentplatform.auth.service.AuthEmailPersistenceService}.
 *
 * <p>Fixed: this Javadoc previously referenced {@code InviteEmailService}
 * and {@code InviteEmailScheduler} — names from before both classes were
 * renamed to their current {@code AuthEmail*} names (this exception now
 * covers both invite and password-reset emails, not just invites).
 */
public class InviteEmailException extends RuntimeException {

    private final String recipientEmail;

    public InviteEmailException(String recipientEmail,
                                String message,
                                Throwable cause) {
        super(message, cause);
        this.recipientEmail = recipientEmail;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }
}