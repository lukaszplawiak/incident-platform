package com.incidentplatform.incident.repository;

import com.incidentplatform.incident.domain.IncidentEventOutbox;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface IncidentEventOutboxRepository
        extends JpaRepository<IncidentEventOutbox, UUID> {

    /**
     * Returns PENDING entries, oldest first, capped by {@code pageable}'s
     * page size — {@link com.incidentplatform.incident.scheduler.IncidentEventOutboxScheduler}
     * uses this to bound how many entries it processes per poll, rather
     * than risking an unbounded batch if a large backlog accumulates
     * (e.g. after a Kafka outage — see {@code IncidentEventOutboxStatus}'s
     * Javadoc: nothing here ever gives up, so a real outage produces a
     * growing PENDING backlog that must be drained incrementally, not all
     * at once).
     */
    @Query("SELECT e FROM IncidentEventOutbox e " +
            "WHERE e.status = IncidentEventOutboxStatus.PENDING " +
            "ORDER BY e.createdAt ASC")
    List<IncidentEventOutbox> findPendingOrderByCreatedAt(Pageable pageable);
}