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
 * Outbox entry for an invite email — written by {@code UserService} and
 * processed by {@code InviteEmailScheduler}.
 *
 * <h2>Outbox Pattern</h2>
 * {@code UserService.createUser()} writes a PENDING entry here in the same
 * transaction that creates the user and the invite token. The HTTP thread
 * returns immediately without sending any email. {@code InviteEmailScheduler}
 * picks up PENDING entries in a separate scheduled thread and sends the
 * actual email with the invite link.
 *
 * <h2>raw_token lifecycle</h2>
 * The raw (unhashed) token is stored here temporarily — it is the only way
 * the scheduler can build the invite link without a second token-generation
 * step. After the email is sent successfully, {@link #markSent()} nulls
 * {@code rawToken} so only the SHA-256 hash in {@code auth_tokens} remains.
 *
 * <p>This creates a short exposure window (typically &lt; 30 seconds) where
 * the raw token is in the database. This is safer than the previous approach
 * of returning the token in the HTTP response, which exposed it to HTTP logs,
 * reverse proxies, and browser devtools permanently.
 *
 * <h2>email denormalisation</h2>
 * The recipient email is stored directly rather than joining through
 * {@code user_id → users.email}. This ensures the scheduler can send the
 * email even if the user is soft-deleted between creation and dispatch.
 */
@Entity
@Table(name = "invite_email_outbox")
public class InviteEmailOutbox {

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
     * Raw (unhashed) invite token — stored temporarily until the email is
     * sent, then NULLed by {@link #markSent()}. Never re-populated after
     * being cleared.
     */
    @Column(name = "raw_token", columnDefinition = "TEXT")
    private String rawToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private InviteEmailStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected InviteEmailOutbox() {}

    /**
     * Creates a new PENDING outbox entry.
     *
     * @param user     the user being invited
     * @param token    the invite token (only hash is persisted in auth_tokens)
     * @param rawToken the raw (unhashed) token — stored temporarily so the
     *                 scheduler can build the invite link
     */
    public static InviteEmailOutbox pending(User user,
                                            AuthToken token,
                                            String rawToken) {
        final InviteEmailOutbox entry = new InviteEmailOutbox();
        entry.id = UUID.randomUUID();
        entry.user = user;
        entry.email = user.getEmail();
        entry.token = token;
        entry.rawToken = rawToken;
        entry.status = InviteEmailStatus.PENDING;
        entry.createdAt = Instant.now();
        return entry;
    }

    /**
     * Marks this entry as successfully sent and NULLs the raw token.
     * After this call, the raw token is no longer accessible from the
     * database — only the hash in {@code auth_tokens} remains.
     */
    public void markSent() {
        this.status = InviteEmailStatus.SENT;
        this.rawToken = null;       // ← security: erase raw token after use
        this.sentAt = Instant.now();
        this.errorMessage = null;
    }

    /**
     * Marks this entry as failed and increments the retry counter.
     * The raw token is retained for subsequent retry attempts.
     */
    public void markFailed(String errorMessage) {
        this.status = InviteEmailStatus.FAILED;
        this.retryCount++;
        this.errorMessage = errorMessage;
    }

    /**
     * Marks this entry as permanently failed after all retry attempts
     * are exhausted. The raw token is NULLed — admin must resend the invite.
     */
    public void markPermanentlyFailed(String errorMessage) {
        this.status = InviteEmailStatus.PERMANENTLY_FAILED;
        this.rawToken = null;       // ← no point keeping it — token will expire
        this.errorMessage = errorMessage;
    }

    public UUID getId()                { return id; }
    public User getUser()              { return user; }
    public String getEmail()           { return email; }
    public AuthToken getToken()        { return token; }
    public String getRawToken()        { return rawToken; }
    public InviteEmailStatus getStatus() { return status; }
    public int getRetryCount()         { return retryCount; }
    public String getErrorMessage()    { return errorMessage; }
    public Instant getCreatedAt()      { return createdAt; }
    public Instant getSentAt()         { return sentAt; }
}