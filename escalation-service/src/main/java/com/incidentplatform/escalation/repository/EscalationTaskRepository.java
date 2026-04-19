package com.incidentplatform.escalation.repository;

import com.incidentplatform.escalation.domain.EscalationTask;
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

    @Query("""
            SELECT t FROM EscalationTask t
            WHERE t.status = 'PENDING'
            AND t.scheduledEscalationAt <= :now
            """)
    List<EscalationTask> findDueForEscalation(@Param("now") Instant now);

    List<EscalationTask> findAllByIncidentId(UUID incidentId);

    boolean existsByIncidentIdAndEscalationLevel(UUID incidentId,
                                                 int escalationLevel);

    Optional<EscalationTask> findByIncidentIdAndEscalationLevel(
            UUID incidentId, int escalationLevel);
}