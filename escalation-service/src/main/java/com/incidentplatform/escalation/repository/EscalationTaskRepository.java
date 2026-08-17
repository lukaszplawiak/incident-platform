package com.incidentplatform.escalation.repository;

import com.incidentplatform.escalation.domain.EscalationTask;
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
public interface EscalationTaskRepository
        extends JpaRepository<EscalationTask, UUID> {

    /**
     * <h2>Fixed (backlog #39): unbounded batch size</h2>
     * Previously returned every due task at once, with no cap — after a
     * scheduler outage or a burst of simultaneous incidents, a single
     * poll cycle could pick up an arbitrarily large batch. Combined with
     * each task involving a synchronous HTTP call to oncall-service (see
     * {@code EscalationScheduler}), an unbounded batch had no ceiling on
     * how long a single cycle could take. {@code pageable} caps it —
     * matching the same bounded-batch pattern already used by
     * {@code IncidentEventOutboxRepository.findPendingOrderByCreatedAt}
     * (incident-service). {@code ORDER BY scheduledEscalationAt ASC}
     * makes the cap deterministic: the most overdue tasks are always
     * processed first, and a backlog too large for one cycle drains
     * oldest-first across subsequent cycles rather than in an
     * unspecified order.
     */
    @Query("""
            SELECT t FROM EscalationTask t
            WHERE t.status = 'PENDING'
            AND t.scheduledEscalationAt <= :now
            ORDER BY t.scheduledEscalationAt ASC
            """)
    List<EscalationTask> findDueForEscalation(@Param("now") Instant now,
                                              Pageable pageable);

    List<EscalationTask> findAllByIncidentId(UUID incidentId);

    boolean existsByIncidentIdAndEscalationLevel(UUID incidentId,
                                                 int escalationLevel);

    Optional<EscalationTask> findByIncidentIdAndEscalationLevel(
            UUID incidentId, int escalationLevel);
}