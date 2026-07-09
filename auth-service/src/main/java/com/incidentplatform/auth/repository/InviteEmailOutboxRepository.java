package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.InviteEmailOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InviteEmailOutboxRepository
        extends JpaRepository<InviteEmailOutbox, UUID> {

    /**
     * Finds PENDING entries older than {@code pendingThreshold}.
     *
     * <p>The threshold (default 30 seconds) prevents the scheduler from
     * racing against a UserService that just committed an entry within the
     * same scheduler cycle.
     */
    @Query("SELECT e FROM InviteEmailOutbox e " +
            "WHERE e.status = 'PENDING' " +
            "AND e.createdAt < :pendingThreshold")
    List<InviteEmailOutbox> findPendingOlderThan(
            @Param("pendingThreshold") Instant pendingThreshold);

    /**
     * Finds FAILED entries that still have remaining retry budget.
     */
    @Query("SELECT e FROM InviteEmailOutbox e " +
            "WHERE e.status = 'FAILED' " +
            "AND e.retryCount < :maxRetries")
    List<InviteEmailOutbox> findFailedWithRemainingRetries(
            @Param("maxRetries") int maxRetries);

    /**
     * Finds the most recent outbox entry for a user — used by the resend
     * flow to determine current invite status before creating a new entry.
     *
     * <p>A user should have at most one active (PENDING or FAILED) outbox
     * entry at a time — enforced by the unique partial index
     * {@code uq_invite_email_outbox_user}. This query is used to find
     * any existing entry regardless of status (including SENT and
     * PERMANENTLY_FAILED) so the resend service can make the correct decision.
     */
    @Query("SELECT e FROM InviteEmailOutbox e " +
            "WHERE e.user.id = :userId " +
            "ORDER BY e.createdAt DESC")
    Optional<InviteEmailOutbox> findLatestByUserId(
            @Param("userId") UUID userId);
}