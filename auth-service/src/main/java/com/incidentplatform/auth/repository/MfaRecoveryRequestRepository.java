package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.MfaRecoveryCloseReason;
import com.incidentplatform.auth.domain.MfaRecoveryRequest;
import com.incidentplatform.auth.domain.MfaRecoveryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Operator MFA recovery requests (backlog #0-90). Every status change is a
 * conditional UPDATE from PENDING whose row count the caller checks: 1 = this
 * call made the change, 0 = the request had already ended (cancelled,
 * executed or expired by someone else first). The updates clear and flush the
 * persistence context (CLAUDE.md, backlog #0-83), so callers re-read anything
 * they need afterwards.
 */
@Repository
public interface MfaRecoveryRequestRepository extends JpaRepository<MfaRecoveryRequest, UUID> {

    /** The user's open request, if any (at most one: a partial unique index). */
    @Query("SELECT r FROM MfaRecoveryRequest r WHERE r.userId = :userId AND r.status = 'PENDING'")
    Optional<MfaRecoveryRequest> findPendingByUserId(@Param("userId") UUID userId);

    /** A tenant's requests, newest first, for the operator's list. */
    Page<MfaRecoveryRequest> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

    /** PENDING requests whose notice went out at or before {@code cutoff}: due to run. */
    @Query("SELECT r FROM MfaRecoveryRequest r WHERE r.status = 'PENDING' "
            + "AND r.noticeSentAt IS NOT NULL AND r.noticeSentAt <= :cutoff "
            + "ORDER BY r.noticeSentAt ASC")
    List<MfaRecoveryRequest> findDue(@Param("cutoff") Instant cutoff, Pageable pageable);

    /** PENDING requests created before {@code cutoff} whose notice was never sent. */
    @Query("SELECT r FROM MfaRecoveryRequest r WHERE r.status = 'PENDING' "
            + "AND r.noticeSentAt IS NULL AND r.createdAt < :cutoff "
            + "ORDER BY r.createdAt ASC")
    List<MfaRecoveryRequest> findUndeliveredBefore(@Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * Records that the request's notice was sent; the waiting period counts
     * from here. Only once, and only while the request is open.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MfaRecoveryRequest r SET r.noticeSentAt = :sentAt "
            + "WHERE r.id = :id AND r.status = 'PENDING' AND r.noticeSentAt IS NULL")
    int recordNoticeSent(@Param("id") UUID id, @Param("sentAt") Instant sentAt);

    /** Whether the request is still open (the scheduler skips the notice of one that is not). */
    @Query("SELECT COUNT(r) > 0 FROM MfaRecoveryRequest r WHERE r.id = :id AND r.status = 'PENDING'")
    boolean isPending(@Param("id") UUID id);

    /**
     * Ends an open request. {@code reason} and {@code closedBy} are null for
     * an execution and for a close by the platform where there is no person.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MfaRecoveryRequest r SET r.status = :status, r.closedAt = :now, "
            + "r.closeReason = :reason, r.closedBy = :closedBy "
            + "WHERE r.id = :id AND r.status = 'PENDING'")
    int close(@Param("id") UUID id, @Param("status") MfaRecoveryStatus status,
              @Param("reason") MfaRecoveryCloseReason reason, @Param("closedBy") UUID closedBy,
              @Param("now") Instant now);

    /**
     * Expires an open request only while its notice is still unsent, so a
     * notice recorded meanwhile keeps the request (review).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MfaRecoveryRequest r SET r.status = 'EXPIRED', r.closedAt = :now, r.closeReason = :reason "
            + "WHERE r.id = :id AND r.status = 'PENDING' AND r.noticeSentAt IS NULL")
    int expireIfUndelivered(@Param("id") UUID id, @Param("reason") MfaRecoveryCloseReason reason,
                            @Param("now") Instant now);
}
