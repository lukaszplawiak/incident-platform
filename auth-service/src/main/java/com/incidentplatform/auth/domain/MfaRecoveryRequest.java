package com.incidentplatform.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A platform operator's request to reset the second factor of a customer
 * tenant's only admin (backlog #0-90).
 *
 * <p>Created PENDING with its notice queued in the auth email outbox. The
 * scheduler records when that notice was sent ({@link #getNoticeSentAt()}),
 * and runs the reset once the waiting period has passed since then, unless
 * the account (the notice's cancel link) or an operator cancelled it first.
 * A request whose notice never went out expires: the account must have been
 * told for the whole waiting period.
 *
 * <p>No {@code @Version}: every change after the INSERT is one conditional
 * UPDATE from PENDING ({@code MfaRecoveryRequestRepository}), checked by its
 * row count, so the database decides between a cancellation and the
 * execution racing each other (the pattern of {@code AuthToken.markUsedIfUnused}
 * and the auth email outbox).
 *
 * <p>{@link Persistable}: the id is assigned in {@link #open}, and without a
 * {@code @Version} Spring Data would take a new request for an existing one and
 * {@code save} would merge (a SELECT first) instead of persisting (backlog
 * #0-47's class of bug; found in review).
 */
@Entity
@Table(name = "mfa_recovery_requests")
public class MfaRecoveryRequest implements Persistable<UUID> {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** The operator who asked for it (a user of platform-operator). */
    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_method", nullable = false, updatable = false, length = 30)
    private MfaVerificationMethod verificationMethod;

    @Column(name = "verification_note", nullable = false, updatable = false, length = 500)
    private String verificationNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MfaRecoveryStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "notice_sent_at")
    private Instant noticeSentAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "close_reason", length = 40)
    private MfaRecoveryCloseReason closeReason;

    @Column(name = "closed_by")
    private UUID closedBy;

    @Transient
    private boolean isNew;

    protected MfaRecoveryRequest() {}

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    /** A new PENDING request. */
    public static MfaRecoveryRequest open(String tenantId, UUID userId, UUID requestedBy,
                                          MfaVerificationMethod method, String note, Instant now) {
        final MfaRecoveryRequest request = new MfaRecoveryRequest();
        request.id                 = UUID.randomUUID();
        request.tenantId           = Objects.requireNonNull(tenantId, "tenantId");
        request.userId             = Objects.requireNonNull(userId, "userId");
        request.requestedBy        = Objects.requireNonNull(requestedBy, "requestedBy");
        request.verificationMethod = Objects.requireNonNull(method, "method");
        request.verificationNote   = Objects.requireNonNull(note, "note");
        request.status             = MfaRecoveryStatus.PENDING;
        request.createdAt          = Objects.requireNonNull(now, "now");
        request.isNew              = true;
        return request;
    }

    @Override
    public UUID getId()                                  { return id; }
    public String getTenantId()                          { return tenantId; }
    public UUID getUserId()                              { return userId; }
    public UUID getRequestedBy()                         { return requestedBy; }
    public MfaVerificationMethod getVerificationMethod() { return verificationMethod; }
    public String getVerificationNote()                  { return verificationNote; }
    public MfaRecoveryStatus getStatus()                 { return status; }
    public Instant getCreatedAt()                        { return createdAt; }
    public Instant getNoticeSentAt()                     { return noticeSentAt; }
    public Instant getClosedAt()                         { return closedAt; }
    public MfaRecoveryCloseReason getCloseReason()       { return closeReason; }
    public UUID getClosedBy()                            { return closedBy; }
}
