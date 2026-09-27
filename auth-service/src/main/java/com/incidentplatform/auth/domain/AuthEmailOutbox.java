package com.incidentplatform.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The intent to send an auth email — an invite or a password reset — to one
 * user (backlog #0-52).
 *
 * <h2>Supported email types</h2>
 * <ul>
 *   <li>{@link AuthEmailType#INVITE} — requested by {@code UserService.createUser()}
 *       and {@code ResendInviteService}. Link: /accept-invite?token=xxx</li>
 *   <li>{@link AuthEmailType#PASSWORD_RESET} — requested by
 *       {@code ForgotPasswordService.initiateReset()}. Link: /reset-password?token=xxx</li>
 * </ul>
 *
 * <h2>Outbox Pattern</h2>
 * A request writes a PENDING row in the same transaction as the business
 * operation and returns. {@code AuthEmailScheduler} sends it later, outside
 * any HTTP request.
 *
 * <h2>Why the row holds no token (backlog #0-52)</h2>
 * The token is created by the scheduler right before each send, and only its
 * SHA-256 hash is stored (in {@code auth_tokens}). Before #0-52 the request
 * created the token and this row kept its raw value until the email went out:
 * a usable credential in plaintext for as long as SMTP was failing (backlog
 * #0-54), a token whose lifetime ran while the email waited, and a row that
 * resend-invite / forgot-password had to close and blank before inserting
 * theirs — two writers of one row. Now the link is valid for its full lifetime
 * from the moment it is sent, and nothing secret is ever written here.
 *
 * <h2>One writer (backlog #0-52)</h2>
 * After the INSERT only the scheduler changes a row, through state-guarded
 * conditional UPDATEs in {@code AuthEmailOutboxRepository}
 * ({@code WHERE id = ? AND status IN ('PENDING', 'FAILED')}). A newer request
 * for the same user and type does not touch this row: the scheduler sees it
 * and marks this one SUPERSEDED. That is why the entity has no setters and no
 * {@code @Version}: there is no second writer to detect, and a conditional
 * UPDATE is the same single-statement guard {@code AuthTokenRepository.markUsedIfUnused}
 * uses (backlog #53).
 *
 * <h2>Retry schedule</h2>
 * {@code nextAttemptAt} is when the scheduler may next try the entry — its
 * creation time for a new one, a backoff step from {@code AuthEmailRetryPolicy}
 * after a failure — and {@code null} once it is terminal (SENT,
 * PERMANENTLY_FAILED, SUPERSEDED); V19's CHECK enforces that. {@code deadline}
 * bounds the retries.
 *
 * <h2>Denormalised email and tenant</h2>
 * The recipient address and tenant are copied from the user so the scheduler
 * reads flat rows (no join) and can set the tenant context per entry.
 */
@Entity
@Table(name = "auth_email_outbox")
public class AuthEmailOutbox {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Column(name = "email", nullable = false, updatable = false)
    private String email;

    /** Determines which email template, link and token type are used. */
    @Enumerated(EnumType.STRING)
    @Column(name = "email_type", nullable = false, updatable = false, length = 30)
    private AuthEmailType emailType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private AuthEmailStatus status;

    /** SMTP attempts made so far, successful or not. */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Until when the request is worth sending: creation + the token lifetime. */
    @Column(name = "deadline", nullable = false, updatable = false)
    private Instant deadline;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected AuthEmailOutbox() {}

    /**
     * A new PENDING request, due at once.
     *
     * @param lifetime how long the request is worth sending — the lifetime of
     *                 the token the email will carry
     */
    public static AuthEmailOutbox request(User user, AuthEmailType emailType, Duration lifetime) {
        Objects.requireNonNull(user.getId(), "user must be persisted");
        final AuthEmailOutbox entry = new AuthEmailOutbox();
        entry.id            = UUID.randomUUID();
        entry.userId        = user.getId();
        entry.tenantId      = user.getTenantId();
        entry.email         = user.getEmail();
        entry.emailType     = Objects.requireNonNull(emailType, "emailType");
        entry.status        = AuthEmailStatus.PENDING;
        entry.createdAt     = Instant.now();
        entry.deadline      = entry.createdAt.plus(lifetime);
        entry.nextAttemptAt = entry.createdAt;
        return entry;
    }

    public UUID getId()                  { return id; }
    public UUID getUserId()              { return userId; }
    public String getTenantId()          { return tenantId; }
    public String getEmail()             { return email; }
    public AuthEmailType getEmailType()  { return emailType; }
    public AuthEmailStatus getStatus()   { return status; }
    public int getAttempts()             { return attempts; }
    public String getErrorMessage()      { return errorMessage; }
    public Instant getCreatedAt()        { return createdAt; }
    public Instant getDeadline()         { return deadline; }
    public Instant getNextAttemptAt()    { return nextAttemptAt; }
    public Instant getSentAt()           { return sentAt; }
}
