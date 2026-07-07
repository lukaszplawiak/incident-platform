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
     */
    @Query("SELECT p FROM Postmortem p " +
            "WHERE p.status = 'GENERATING' " +
            "AND p.createdAt < :stuckThreshold")
    List<Postmortem> findStuckGenerating(
            @Param("stuckThreshold") Instant stuckThreshold);

    /**
     * Finds FAILED postmortems that still have remaining retry attempts.
     */
    @Query("SELECT p FROM Postmortem p " +
            "WHERE p.status = 'FAILED' " +
            "AND p.retryCount < :maxRetryAttempts")
    List<Postmortem> findFailedWithRemainingRetries(
            @Param("maxRetryAttempts") int maxRetryAttempts);
}