package com.incidentplatform.postmortem.repository;

import com.incidentplatform.postmortem.domain.Postmortem;
import org.springframework.data.domain.Page;
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
public interface PostmortemRepository extends JpaRepository<Postmortem, UUID> {

    Optional<Postmortem> findByIncidentIdAndTenantId(UUID incidentId, String tenantId);

    Page<Postmortem> findByTenantIdOrderByCreatedAtDesc(String tenantId,
                                                        Pageable pageable);

    boolean existsByIncidentId(UUID incidentId);

    /**
     * Finds GENERATING postmortems that have been stuck for longer than
     * {@code stuckThreshold}.
     *
     * <p>A record enters GENERATING when the Kafka consumer writes the outbox
     * entry. The scheduler picks it up and calls Gemini. If the process
     * crashes between those two steps the record stays in GENERATING forever —
     * this query finds those stuck records so the scheduler can process them.
     *
     * <p>The {@code stuckThreshold} (e.g. 2 minutes after creation) gives the
     * scheduler a safety margin to avoid racing against a consumer that just
     * wrote the record and has not yet been picked up by the first scheduler
     * run.
     *
     * <h2>Fixed (backlog #48): {@code Pageable} added — previously unbounded</h2>
     * Each row costs a real Gemini API call (3–15s) in the scheduler that
     * consumes this — an unbounded result set could genuinely exceed
     * {@code PostmortemRetryScheduler.processGenerating()}'s
     * {@code lockAtMostFor}. Same {@code Pageable}-batching pattern as
     * {@code EscalationTaskRepository.findDueForEscalation} (backlog #39).
     * Explicit {@code ORDER BY p.createdAt ASC} added alongside the
     * {@code Pageable} — without a stable order, {@code LIMIT}-based paging
     * has no guaranteed row selection across repeated calls (every
     * scheduler tick), risking the same arbitrary subset being picked every
     * time while older stuck records are starved.
     */
    @Query("SELECT p FROM Postmortem p " +
            "WHERE p.status = 'GENERATING' " +
            "AND p.createdAt < :stuckThreshold " +
            "ORDER BY p.createdAt ASC")
    List<Postmortem> findStuckGenerating(
            @Param("stuckThreshold") Instant stuckThreshold,
            Pageable pageable);

    /**
     * Finds FAILED postmortems that still have remaining retry attempts.
     *
     * <h2>Fixed (backlog #48): {@code Pageable} added — same reasoning as
     * {@link #findStuckGenerating}</h2>
     * Caps how many rows {@code PostmortemRetryScheduler.retryFailedPostmortems()}
     * processes per run, protecting its {@code lockAtMostFor}. Explicit
     * {@code ORDER BY p.createdAt ASC} for the same stable-paging reason.
     */
    @Query("SELECT p FROM Postmortem p " +
            "WHERE p.status = 'FAILED' " +
            "AND p.retryCount < :maxRetryAttempts " +
            "ORDER BY p.createdAt ASC")
    List<Postmortem> findFailedWithRemainingRetries(
            @Param("maxRetryAttempts") int maxRetryAttempts,
            Pageable pageable);
}