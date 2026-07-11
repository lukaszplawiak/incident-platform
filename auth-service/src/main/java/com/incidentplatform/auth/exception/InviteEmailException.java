package com.incidentplatform.auth.exception;

/**
 * Thrown by {@link InviteEmailService} when sending an invite email fails.
 * Caught by {@link com.incidentplatform.auth.scheduler.InviteEmailScheduler}
 * which marks the outbox entry as FAILED and schedules a retry.
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