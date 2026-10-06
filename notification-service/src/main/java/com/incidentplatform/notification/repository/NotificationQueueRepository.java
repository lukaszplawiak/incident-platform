package com.incidentplatform.notification.repository;

import com.incidentplatform.notification.domain.NotificationQueueEntry;
import com.incidentplatform.notification.domain.NotificationQueueStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
     * The paused-tenants table {@link #findPendingOlderThan} names (backlog
     * #0-82, step 2b); {@code NotificationPausableWork} reports it, and startup
     * fails unless it is {@code tenant-pause.table}, the table the sync writes.
     */
    String PAUSED_TABLE = "notification_paused_tenants";

    /**
     * Finds PENDING outbox entries older than {@code pendingThreshold}.
     *
     * <p>The threshold (e.g. 30 seconds after creation) gives the scheduler
     * a safety margin to avoid racing against a consumer that just wrote the
     * entry and is still within the same scheduler cycle.
     *
     * <p>Oldest first and capped by {@code pageable} (backlog #0-10, the same
     * shape as {@code EscalationScheduler}'s {@code scheduler-batch-size}, backlog
     * #39). Since an entry now stays PENDING while oncall-service is unavailable
     * (backlog #0-19), the list would otherwise grow with the outage and be
     * re-read whole every cycle, and under the run's processing budget an
     * unordered scan could leave the same tail entries unprocessed run after run.
     * Served by the partial index {@code idx_notification_queue_status_created
     * (status, created_at) WHERE status = 'PENDING'}.
     *
     * <h2>Backlog #0-82, step 2b: a suspended tenant's entries are left out</h2>
     * Here, in the query, not skipped in the scheduler: they are the oldest, so
     * skipped after the LIMIT they would fill every batch and hold up every
     * other tenant's notifications. They wait, in order, and are all sent once
     * the tenant is resumed. Native, because the paused table
     * ({@link #PAUSED_TABLE}, which must be {@code tenant-pause.table}) is
     * {@code shared}'s, written over JDBC, and has no entity.
     */
    @Query(value = "SELECT e.* FROM notification_queue e "
            + "WHERE e.status = 'PENDING' "
            + "AND e.created_at < :pendingThreshold "
            + "AND NOT EXISTS (SELECT 1 FROM " + PAUSED_TABLE
            + " p WHERE p.tenant_id = e.tenant_id) "
            + "ORDER BY e.created_at ASC",
            nativeQuery = true)
    List<NotificationQueueEntry> findPendingOlderThan(
            @Param("pendingThreshold") Instant pendingThreshold,
            Pageable pageable);

    /**
     * The tenants with a PENDING entry, paused or not: the candidates the pause
     * sync asks auth-service about (backlog #0-82, step 2b).
     */
    @Query(value = "SELECT DISTINCT e.tenant_id FROM notification_queue e WHERE e.status = 'PENDING' "
            + "ORDER BY e.tenant_id", nativeQuery = true)
    List<String> findTenantsWithPendingEntries();

    /**
     * On resumption (backlog #0-82, step 2b): a PENDING entry whose routing
     * lookup had failed before the pause starts its retry window (backlog
     * #0-19) again. The window runs from the first failed lookup, and it ran on
     * while the entry was held, so the first failure after the resumption would
     * otherwise give it up as UNDELIVERABLE at once. A new window only ever
     * makes it longer, as the scheduler's own comment on recording the failure
     * says.
     *
     * <p>A second writer of a queue entry besides the scheduler: the scheduler
     * reads no row of a paused tenant, and this runs in the transaction that
     * ends the pause, before the scheduler can read the rows again. An entry the
     * scheduler loaded before the tenant was paused and is still processing
     * when the tenant is resumed again (within one run) can write its old value
     * back: the window is then the one it would have had without the pause.
     *
     * @return the entries whose window starts again
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE notification_queue
               SET first_lookup_failure_at = NULL
             WHERE tenant_id = :tenantId
               AND status = 'PENDING'
               AND first_lookup_failure_at IS NOT NULL
            """, nativeQuery = true)
    int restartLookupRetryWindows(@Param("tenantId") String tenantId);

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