package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthEmailOutboxRepository
        extends JpaRepository<AuthEmailOutbox, UUID> {

    /**
     * Finds PENDING entries older than {@code pendingThreshold}.
     * Optionally filtered by {@code emailType} — pass {@code null} for all types.
     *
     * <p>{@code JOIN FETCH e.user} — needed because {@code AuthEmailScheduler}
     * processes each returned entry across a gap that includes a real SMTP
     * send (see {@code AuthEmailPersistenceService}'s Javadoc for why that
     * gap can no longer be inside one shared transaction). Without eagerly
     * fetching {@code user} here, entries become detached the moment this
     * query's own short transaction closes, and {@code entry.getUser()}
     * — a {@code FetchType.LAZY} association — would throw
     * {@code LazyInitializationException} when {@code processOne} logs
     * {@code entry.getUser().getId()} afterward. Same pattern already used
     * elsewhere in this module (e.g. {@code ApiKeyRepository.findActiveByHash}'s
     * {@code LEFT JOIN FETCH k.ownerUser}) — also avoids an N+1 lazy-load
     * per entry in the batch as a side benefit.
     *
     * <h2>Fixed (backlog #54): {@code Pageable} added — previously
     * unbounded</h2>
     * Each row costs a real, blocking SMTP send in the scheduler that
     * consumes this — an unbounded result set could genuinely exceed
     * {@code AuthEmailScheduler}'s {@code lockAtMostFor = "4m"}. Same
     * {@code Pageable}-batching pattern as
     * {@code EscalationTaskRepository.findDueForEscalation} (backlog
     * #39) and {@code PostmortemRepository.findStuckGenerating} (backlog
     * #48). Explicit {@code ORDER BY e.createdAt ASC} added alongside
     * the {@code Pageable} — without a stable order, {@code LIMIT}-based
     * paging has no guaranteed row selection across repeated calls
     * (every scheduler tick), risking the same arbitrary subset being
     * picked every time while older pending entries are starved.
     */
    @Query("SELECT e FROM AuthEmailOutbox e " +
            "JOIN FETCH e.user " +
            "WHERE e.status = 'PENDING' " +
            "AND e.createdAt < :pendingThreshold " +
            "AND (:emailType IS NULL OR e.emailType = :emailType) " +
            "ORDER BY e.createdAt ASC")
    List<AuthEmailOutbox> findPendingOlderThan(
            @Param("pendingThreshold") Instant pendingThreshold,
            @Param("emailType") AuthEmailType emailType,
            Pageable pageable);

    /**
     * Finds FAILED entries that still have remaining retry budget.
     * Optionally filtered by {@code emailType}.
     *
     * <p>{@code JOIN FETCH e.user} — same reasoning as
     * {@link #findPendingOlderThan}.
     *
     * <h2>Fixed (backlog #54): {@code Pageable} added — same reasoning
     * as {@link #findPendingOlderThan}</h2>
     */
    @Query("SELECT e FROM AuthEmailOutbox e " +
            "JOIN FETCH e.user " +
            "WHERE e.status = 'FAILED' " +
            "AND e.retryCount < :maxRetries " +
            "AND (:emailType IS NULL OR e.emailType = :emailType) " +
            "ORDER BY e.createdAt ASC")
    List<AuthEmailOutbox> findFailedWithRemainingRetries(
            @Param("maxRetries") int maxRetries,
            @Param("emailType") AuthEmailType emailType,
            Pageable pageable);

    /**
     * Finds the most recent outbox entry for a user and email type.
     * Used by resend-invite and forgot-password flows to check
     * current status before creating a new entry.
     */
    @Query("SELECT e FROM AuthEmailOutbox e " +
            "WHERE e.user.id = :userId " +
            "AND e.emailType = :emailType " +
            "ORDER BY e.createdAt DESC")
    Optional<AuthEmailOutbox> findLatestByUserIdAndType(
            @Param("userId") UUID userId,
            @Param("emailType") AuthEmailType emailType);
}