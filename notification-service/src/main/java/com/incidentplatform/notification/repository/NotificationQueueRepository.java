package com.incidentplatform.notification.repository;

import com.incidentplatform.notification.domain.NotificationQueueEntry;
import com.incidentplatform.notification.domain.NotificationQueueStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationQueueRepository
        extends JpaRepository<NotificationQueueEntry, UUID> {

    /**
     * Finds PENDING outbox entries older than {@code pendingThreshold}.
     *
     * <p>The threshold (e.g. 30 seconds after creation) gives the scheduler
     * a safety margin to avoid racing against a consumer that just wrote the
     * entry and is still within the same scheduler cycle.
     */
    @Query("SELECT e FROM NotificationQueueEntry e " +
            "WHERE e.status = 'PENDING' " +
            "AND e.createdAt < :pendingThreshold")
    List<NotificationQueueEntry> findPendingOlderThan(
            @Param("pendingThreshold") Instant pendingThreshold);

    /**
     * Idempotency check — prevents duplicate queue entries for the same
     * incident + event + escalation level combination. Called by the
     * consumer before enqueue.
     *
     * <p>{@code escalationLevel} is part of the key because every
     * escalation is an {@code IncidentEscalatedEvent} for the same
     * incident; keyed on incident + event type alone, the level-2 entry was
     * discarded as a duplicate of level 1. It is {@code 0} for every event
     * type that is not an escalation. {@code tenantId} keeps the check
     * tenant-scoped like every other query in this service.
     */
    boolean existsByIncidentIdAndTenantIdAndEventTypeAndEscalationLevel(
            UUID incidentId, String tenantId, String eventType,
            int escalationLevel);
}