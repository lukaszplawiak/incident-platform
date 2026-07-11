package com.incidentplatform.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox entry for auth-domain transactional emails.
 *
 * <h2>Supported email types</h2>
 * <ul>
 *   <li>{@link AuthEmailType#INVITE} — written by {@code UserService.createUser()},
 *       sent by {@code AuthEmailScheduler}. Link: /accept-invite?token=xxx</li>
 *   <li>{@link AuthEmailType#PASSWORD_RESET} — written by
 *       {@code ForgotPasswordService.initiateReset()}, sent by
 *       {@code AuthEmailScheduler}. Link: /reset-password?token=xxx</li>
 * </ul>
 *
 * <h2>Outbox Pattern</h2>
 * Writers create a PENDING entry in the same transaction as the business
 * operation (user creation or password reset initiation) and return
 * immediately. {@code AuthEmailScheduler} picks up PENDING entries in a
 * dedicated scheduled thread and sends the actual email via SMTP.
 *
 * <h2>raw_token lifecycle</h2>
 * The raw (unhashed) token is stored temporarily — it is the only way the
 * scheduler can build the email link. After the email is sent successfully,
 * {@link #markSent()} nulls {@code rawToken} so only the SHA-256 hash in
 * {@code auth_tokens} remains. On permanent failure, {@link #markPermanentlyFailed}
 * also nulls it — the token will expire and the user must restart the flow.
 *
 * <h2>email denormalisation</h2>
 * The recipient email is stored directly rather than joining through
 * {@code user_id → users.email}. This ensures delivery even if the user is
 * soft-deleted between outbox write and scheduler dispatch.
 */
@Entity
@Table(name = "auth_email_outbox")
public class AuthEmailOutbox {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /**
     * Denormalised recipient address — see class Javadoc for rationale.
     */
    @Column(name = "email", nullable = false, updatable = false)
    private String email;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "token_id", nullable = false, updatable = false)
    private AuthToken token;

    /**
     * Raw (unhashed) token — stored temporarily until the email is sent,
     * then NULLed by {@link #markSent()}. Never re-populated after clearing.
     */
    @Column(name = "raw_token", columnDefinition = "TEXT")
    private String rawToken;

    /**
     * Determines which email template and endpoint link to use.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "email_type", nullable = false, updatable = false, length = 30)
    private AuthEmailType emailType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private AuthEmailStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected AuthEmailOutbox() {}

    /**
     * Creates a PENDING outbox entry for an invite email.
     */
    public static AuthEmailOutbox invitePending(User user,
                                                AuthToken token,
                                                String rawToken) {
        return pending(user, token, rawToken, AuthEmailType.INVITE);
    }

    /**
     * Creates a PENDING outbox entry for a password reset email.
     */
    public static AuthEmailOutbox passwordResetPending(User user,
                                                       AuthToken token,
                                                       String rawToken) {
        return pending(user, token, rawToken, AuthEmailType.PASSWORD_RESET);
    }

    private static AuthEmailOutbox pending(User user, AuthToken token,
                                           String rawToken,
                                           AuthEmailType emailType) {
        final AuthEmailOutbox entry = new AuthEmailOutbox();
        entry.id        = UUID.randomUUID();
        entry.user      = user;
        entry.email     = user.getEmail();
        entry.token     = token;
        entry.rawToken  = rawToken;
        entry.emailType = emailType;
        entry.status    = AuthEmailStatus.PENDING;
        entry.createdAt = Instant.now();
        return entry;
    }

    /**
     * Marks this entry as successfully sent and NULLs the raw token.
     * After this call, the raw token is no longer accessible from the
     * database — only the hash in {@code auth_tokens} remains.
     */
    public void markSent() {
        this.status       = AuthEmailStatus.SENT;
        this.rawToken     = null;
        this.sentAt       = Instant.now();
        this.errorMessage = null;
    }

    /**
     * Marks this entry as failed and increments the retry counter.
     * The raw token is retained for subsequent retry attempts.
     */
    public void markFailed(String errorMessage) {
        this.status       = AuthEmailStatus.FAILED;
        this.retryCount++;
        this.errorMessage = errorMessage;
    }

    /**
     * Marks this entry as permanently failed and NULLs the raw token.
     * The user must restart the flow (resend-invite or request new reset).
     */
    public void markPermanentlyFailed(String errorMessage) {
        this.status       = AuthEmailStatus.PERMANENTLY_FAILED;
        this.rawToken     = null;
        this.errorMessage = errorMessage;
    }

    public UUID getId()                  { return id; }
    public User getUser()                { return user; }
    public String getEmail()             { return email; }
    public AuthToken getToken()          { return token; }
    public String getRawToken()          { return rawToken; }
    public AuthEmailType getEmailType()  { return emailType; }
    public AuthEmailStatus getStatus() { return status; }
    public int getRetryCount()           { return retryCount; }
    public String getErrorMessage()      { return errorMessage; }
    public Instant getCreatedAt()        { return createdAt; }
    public Instant getSentAt()           { return sentAt; }
}