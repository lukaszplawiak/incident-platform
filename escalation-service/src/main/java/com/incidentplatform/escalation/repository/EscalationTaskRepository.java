package com.incidentplatform.escalation.repository;

import com.incidentplatform.escalation.domain.EscalationTask;
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

@Repository
public interface EscalationTaskRepository
        extends JpaRepository<EscalationTask, UUID> {

    /**
     * The paused-tenants table the queries below name (backlog #0-82, step 2b);
     * {@code EscalationPausableWork} reports it, and startup fails unless it is
     * {@code tenant-pause.table}, the table the sync writes.
     */
    String PAUSED_TABLE = "escalation_paused_tenants";

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
     *
     * <h2>Backlog #0-82, step 2b: a suspended tenant's tasks are left out</h2>
     * Here, in the query, not skipped in the scheduler: they are the most
     * overdue, so skipped after the LIMIT they would fill every batch and hold
     * up every other tenant's escalations. Native, because the paused table
     * ({@link #PAUSED_TABLE}, which must be {@code tenant-pause.table}) is
     * {@code shared}'s, written over JDBC, and has no entity.
     */
    @Query(value = "SELECT t.* FROM escalation_tasks t "
            + "WHERE t.status = 'PENDING' "
            + "AND t.scheduled_escalation_at <= :now "
            + "AND NOT EXISTS (SELECT 1 FROM " + PAUSED_TABLE
            + " p WHERE p.tenant_id = t.tenant_id) "
            + "ORDER BY t.scheduled_escalation_at ASC",
            nativeQuery = true)
    List<EscalationTask> findDueForEscalation(@Param("now") Instant now,
                                              Pageable pageable);

    /**
     * The tenants with a PENDING task, paused or not: the candidates the pause
     * sync asks auth-service about (backlog #0-82, step 2b). A task not yet due
     * counts, so a tenant is paused before its timers come due.
     */
    @Query(value = "SELECT DISTINCT t.tenant_id FROM escalation_tasks t WHERE t.status = 'PENDING' "
            + "ORDER BY t.tenant_id", nativeQuery = true)
    List<String> findTenantsWithPendingTasks();

    /**
     * Stops the tenant's escalation timers for the length of its pause, on
     * resumption (backlog #0-82, step 2b): a PENDING task gets the time it had
     * left when the tenant was suspended, so the tenant, which could not
     * acknowledge anything while suspended, is not escalated the moment it is
     * back.
     *
     * <ul>
     *   <li>A timer running at the suspension ({@code incident_opened_at}, the
     *       timer's start, before {@code pausedAt}) moves on by
     *       {@code now - pausedAt}.</li>
     *   <li>A timer started during the pause (the consumer goes on creating
     *       tasks) moves on by {@code now - start}: its full timeout from the
     *       resumption.</li>
     *   <li>A task already due at the suspension is left as it is: it was late
     *       before it and goes on the next run.</li>
     * </ul>
     *
     * <p>{@code pausedAt} is auth-service's {@code suspended_at}, the
     * suspension's own time, so it does not matter how late the pause sync saw
     * it (found in review: measured from the sync, a task that came due while
     * the scheduler's guard held it but before a late sync paused the tenant
     * escalated at once on resumption). {@code now()} and {@code suspended_at}
     * are both the one database's clock; the move is never negative all the same
     * (review: a pause start after {@code now()} would pull a timer earlier).
     *
     * <p>{@code version} goes up, so a task the scheduler read before this
     * commits loses its {@code saveAndFlush} (backlog #38) instead of escalating
     * on its old time. Run by {@code PausedTenantsSync} in the transaction that
     * deletes the tenant's paused row.
     *
     * @return the tasks moved
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE escalation_tasks t
               SET scheduled_escalation_at = t.scheduled_escalation_at
                       + GREATEST(interval '0',
                                  now() - GREATEST(CAST(:pausedAt AS timestamptz), t.incident_opened_at)),
                   version = t.version + 1,
                   updated_at = now()
             WHERE t.tenant_id = :tenantId
               AND t.status = 'PENDING'
               AND t.scheduled_escalation_at > CAST(:pausedAt AS timestamptz)
            """, nativeQuery = true)
    int resumePendingTimers(@Param("tenantId") String tenantId, @Param("pausedAt") Instant pausedAt);

    List<EscalationTask> findAllByIncidentId(UUID incidentId);

    boolean existsByIncidentIdAndEscalationLevel(UUID incidentId,
                                                 int escalationLevel);

    Optional<EscalationTask> findByIncidentIdAndEscalationLevel(
            UUID incidentId, int escalationLevel);
}