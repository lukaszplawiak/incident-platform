package com.incidentplatform.escalation.scheduler;

import com.incidentplatform.escalation.client.OncallServiceClient;
import com.incidentplatform.escalation.domain.EscalationTask;
import com.incidentplatform.escalation.dto.OncallUserDto;
import com.incidentplatform.escalation.repository.EscalationTaskRepository;
import com.incidentplatform.escalation.service.EscalationService;
import com.incidentplatform.escalation.service.EscalationTaskPersistenceService;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.events.IncidentEscalatedEvent;
import com.incidentplatform.shared.events.IncidentEventKafkaSender;
import com.incidentplatform.shared.events.IncidentEventTypes;
import com.incidentplatform.shared.security.TenantContext;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <h2>Fixed (backlog #39): whole batch processed in one long-running transaction</h2>
 * {@code checkAndEscalate()} previously carried {@code @Transactional} on
 * the whole method — including the loop that called
 * {@code OncallServiceClient.getCurrentOncall(...)}, a real, blocking
 * HTTP call — holding one database connection open for the combined
 * duration of every on-call lookup in the batch. Under a large backlog
 * (e.g. after a scheduler outage, or a burst of simultaneous incidents)
 * or a slow/degraded oncall-service, this risked holding a connection
 * for a long time, potentially exhausting the pool.
 *
 * <p>Before implementing the fix, compared how this exact problem is
 * already solved elsewhere in this codebase: {@code AuthEmailScheduler}
 * (auth-service), {@code NotificationScheduler} (notification-service),
 * {@code PostmortemRetryScheduler} (postmortem-service), and
 * {@code IncidentEventOutboxScheduler} (incident-service, backlog #36)
 * all remove {@code @Transactional} from the scheduled method and
 * delegate each item's write to a small, dedicated persistence service
 * with its own short transaction. Synthesized the best specific
 * practices across all four rather than copying just one:
 * {@code AuthEmailScheduler}/{@code PostmortemRetryScheduler}'s pattern
 * of precisely-named persistence methods (over
 * {@code NotificationScheduler}'s weaker inline
 * {@code repository.save()} in the scheduler itself), and
 * {@code IncidentEventOutboxScheduler}'s bounded batch size via
 * {@code Pageable} (a gap in all three of the others). Deliberately did
 * NOT copy the "pending threshold" pattern common to the other three —
 * it exists there to avoid racing a writer that just inserted a fresh
 * row; {@code findDueForEscalation}'s own
 * {@code scheduledEscalationAt <= :now} condition already serves that
 * purpose here structurally (a task can't be picked up before its
 * scheduled time), making a separate threshold redundant.
 *
 * <p>Fixed by removing {@code @Transactional} entirely —
 * {@code findDueForEscalation} is already its own short, auto-committing
 * transaction (Spring Data JPA's default behavior for a repository query
 * method called outside any open transaction), and each task's write now
 * goes through {@link EscalationTaskPersistenceService}. See that
 * class's Javadoc for the persistence side of this fix.
 */
@Component
public class EscalationScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(EscalationScheduler.class);

    private static final String SERVICE_NAME = "escalation-service";

    private static final String ESCALATION_ROLE_LEVEL_1 = "SECONDARY";
    private static final String ESCALATION_ROLE_LEVEL_2 = "MANAGER";

    private final EscalationTaskRepository taskRepository;
    private final EscalationTaskPersistenceService persistenceService;
    private final IncidentEventKafkaSender kafkaSender;
    private final EscalationService escalationService;
    private final AuditEventPublisher auditEventPublisher;
    private final OncallServiceClient oncallServiceClient;
    private final int batchSize;

    public EscalationScheduler(
            EscalationTaskRepository taskRepository,
            EscalationTaskPersistenceService persistenceService,
            IncidentEventKafkaSender kafkaSender,
            EscalationService escalationService,
            AuditEventPublisher auditEventPublisher,
            OncallServiceClient oncallServiceClient,
            // Fixed (backlog #39): caps how many due tasks one poll cycle
            // processes — matches IncidentEventOutboxRepository's bounded-
            // batch pattern (incident-service). Without this, a large
            // backlog (scheduler outage, burst of simultaneous incidents)
            // had no ceiling on how long a single cycle — and therefore
            // how long each task's synchronous oncall-service HTTP call
            // sequence — could take. A backlog too large for one batch
            // simply drains oldest-first (see findDueForEscalation's
            // ORDER BY) across subsequent cycles instead.
            @Value("${escalation.scheduler-batch-size:100}") int batchSize) {
        this.taskRepository = taskRepository;
        this.persistenceService = persistenceService;
        this.kafkaSender = kafkaSender;
        this.escalationService = escalationService;
        this.auditEventPublisher = auditEventPublisher;
        this.oncallServiceClient = oncallServiceClient;
        this.batchSize = batchSize;
    }

    /**
     * Finds escalation tasks due across all tenants and escalates each one.
     *
     * <p>{@code findDueForEscalation()} deliberately queries across all
     * tenants in a single statement — this service runs as one shared
     * process against one shared database (not a database-per-tenant
     * deployment), so a single cross-tenant query here is the correct,
     * efficient pattern. Running N separate per-tenant queries instead
     * would be an N+1-style anti-pattern, not an improvement.
     *
     * <p>What matters is that {@link TenantContext} is set for the duration
     * of processing each individual task — see {@link #escalate}.
     *
     * <p>Deliberately NOT {@code @Transactional} at this level — see this
     * class's own Javadoc for the full account. Each task's database
     * write is its own short transaction via
     * {@link EscalationTaskPersistenceService}, opened only after the
     * (potentially slow) oncall-service HTTP call in {@link #escalate}
     * has already completed.
     */
    @Scheduled(
            fixedDelayString = "${escalation.scheduler-interval-ms:60000}",
            initialDelayString = "30000"
    )
    @SchedulerLock(
            name = "escalation-service:checkAndEscalate",
            lockAtMostFor = "5m",
            lockAtLeastFor = "10s"
    )
    public void checkAndEscalate() {
        final List<EscalationTask> dueTasks = taskRepository.findDueForEscalation(
                Instant.now(), PageRequest.of(0, batchSize));

        if (dueTasks.isEmpty()) {
            log.debug("Escalation check: no tasks due for escalation");
            return;
        }

        log.info("Escalation check: found {} tasks due for escalation",
                dueTasks.size());

        for (final EscalationTask task : dueTasks) {
            // TenantContext is set for the duration of processing this single
            // task — every log line emitted by escalate() (and anything it
            // calls, including kafkaSender.send() and
            // escalationService.scheduleLevel2Escalation()) automatically
            // carries the correct tenantId in MDC, matching the pattern
            // already used by every Kafka consumer in this codebase. Cleared
            // in finally so a failure for one tenant's task can never leak
            // its context into the next iteration.
            TenantContext.set(task.getTenantId());
            try {
                escalate(task);
            } catch (Exception e) {
                log.error("Failed to escalate task: incidentId={}, error={}",
                        task.getIncidentId(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void escalate(EscalationTask task) {
        // ── Resolve on-call engineer ──────────────────────────────────
        // Determine which role to page based on escalation level:
        //   Level 1 → SECONDARY (primary was already paged at incident creation)
        //   Level 2 → MANAGER
        //
        // Fixed (backlog #39): this HTTP call now happens with no database
        // transaction open at all — checkAndEscalate() no longer wraps
        // this method in one. See this class's own Javadoc for the full
        // account.
        final String role = task.getEscalationLevel() == 1
                ? ESCALATION_ROLE_LEVEL_1 : ESCALATION_ROLE_LEVEL_2;

        UUID escalateTo = null;

        if (task.getTeamId() != null) {
            final java.util.Optional<OncallUserDto> oncallUser =
                    oncallServiceClient.getCurrentOncall(
                            task.getTenantId(), task.getTeamId(), role);

            if (oncallUser.isPresent()) {
                try {
                    escalateTo = UUID.fromString(oncallUser.get().userId());
                    log.info("On-call resolved: userId={}, email={}, " +
                                    "teamId={}, role={}, incidentId={}",
                            escalateTo, oncallUser.get().email(),
                            task.getTeamId(), role, task.getIncidentId());
                } catch (IllegalArgumentException e) {
                    log.warn("oncall-service returned invalid userId: {}",
                            oncallUser.get().userId());
                }
            } else {
                log.warn("No active on-call found: teamId={}, role={}, " +
                                "tenant={}, incidentId={} — escalating without assignee",
                        task.getTeamId(), role, task.getTenantId(),
                        task.getIncidentId());
            }
        } else {
            log.warn("EscalationTask has no teamId — skipping on-call routing: " +
                            "incidentId={}, tenant={}. Configure an Integration " +
                            "with a team assignment for automatic routing.",
                    task.getIncidentId(), task.getTenantId());
        }

        final IncidentEscalatedEvent event = new IncidentEscalatedEvent(
                task.getIncidentId(),
                task.getTenantId(),
                escalateTo,
                task.getEscalationLevel(),
                task.getSeverity(),
                task.getTitle(),
                Instant.now()
        );

        // ── Ordering: persist state BEFORE publishing to Kafka ───────────────
        //
        // persistenceService.markEscalated(task) happens first so that if
        // kafkaSender.send() throws afterwards, the ESCALATED status is
        // already durably committed — in its own short transaction (backlog
        // #39), independent of and already complete by the time this line
        // returns, not waiting on any outer transaction to commit later.
        // findDueForEscalation() will therefore NOT return this task again on
        // the next scheduler tick — preventing duplicate notifications
        // (double SMS / email / Slack) to the on-call engineer.
        //
        // Trade-off — at-most-once Kafka delivery:
        // If the process crashes between markEscalated() and kafkaSender.send(),
        // the task is marked ESCALATED in the DB but the Kafka event was never
        // sent — the on-call engineer will not be notified for this escalation
        // level.
        //
        // TODO: For true exactly-once delivery, replace the direct kafkaSender.send()
        //  call with the Transactional Outbox Pattern:
        //  1. Persist an OutboxEvent row (same DB transaction as markEscalated()).
        //  2. A separate OutboxEventRelay scheduler polls PENDING outbox rows,
        //     sends them to Kafka, then marks them SENT.
        //  This guarantees that state change and Kafka publish either both happen
        //  or neither does, even across process crashes. Same pattern already
        //  implemented for incidents.lifecycle in incident-service (backlog #36) —
        //  IncidentEventOutboxScheduler/IncidentEventOutboxPersistenceService is a
        //  ready-made reference implementation to adapt here.
        //  Justified when running multiple instances (Kubernetes HPA) or when
        //  duplicate on-call notifications have business/regulatory consequences.
        // ────────────────────────────────────────────────────────────────────
        //
        // Fixed (backlog #38): markEscalated() uses saveAndFlush() internally,
        // not save() — forcing the @Version check to happen synchronously,
        // before kafkaSender.send() below, so a task that
        // EscalationService.cancelEscalation() concurrently modified (on the
        // Kafka listener thread, independent of and unprotected by this
        // method's ShedLock — see EscalationTask's own Javadoc for the full
        // account) is caught and skipped BEFORE any notification is sent for
        // it, not after.
        try {
            persistenceService.markEscalated(task);
        } catch (OptimisticLockingFailureException e) {
            // Not an error — this task was concurrently modified since
            // findDueForEscalation() read it, almost certainly cancelled by
            // EscalationService.cancelEscalation() reacting to an
            // IncidentAcknowledgedEvent that arrived in the same narrow
            // window. Skip this task entirely: no Kafka event, no audit
            // entry, no level-2 scheduling — the cancellation is what
            // should win, and it already did, in the database.
            log.info("Escalation task was concurrently modified (likely " +
                            "cancelled after ACK) — skipping: incidentId={}, " +
                            "tenant={}, escalationLevel={}",
                    task.getIncidentId(), task.getTenantId(),
                    task.getEscalationLevel());
            return;
        }

        // Publishes to incidents-lifecycle with X-Event-Type header so that
        // notification-service routes this event to EMAIL/SLACK/SMS.
        kafkaSender.send(event, IncidentEventTypes.INCIDENT_ESCALATED);

        log.info("Incident escalated: incidentId={}, tenant={}, " +
                        "severity={}, escalationLevel={}",
                task.getIncidentId(), task.getTenantId(),
                task.getSeverity(), task.getEscalationLevel());

        auditEventPublisher.publishIncident(
                task.getIncidentId(), task.getTenantId(),
                AuditEventTypes.ESCALATION_FIRED, SERVICE_NAME,
                String.format("Escalation level %d fired — %s notified. " +
                                "No ACK within timeout for severity %s.",
                        task.getEscalationLevel(), role,
                        task.getSeverity().name()),
                Map.of("escalationLevel", task.getEscalationLevel(),
                        "role", role,
                        "severity", task.getSeverity().name())
        );

        if (!task.isMaxLevel()) {
            escalationService.scheduleLevel2Escalation(
                    task.getIncidentId(),
                    task.getTenantId(),
                    task.getTeamId(),
                    task.getSeverity(),
                    task.getTitle()
            );

            log.info("Level 2 escalation scheduled: incidentId={}, " +
                            "tenant={}, severity={}",
                    task.getIncidentId(), task.getTenantId(),
                    task.getSeverity());

            auditEventPublisher.publishIncident(
                    task.getIncidentId(), task.getTenantId(),
                    AuditEventTypes.ESCALATION_SCHEDULED, SERVICE_NAME,
                    String.format("Level 2 escalation scheduled — MANAGER " +
                                    "will be notified if no ACK within %d minutes.",
                            EscalationTask.resolveTimeout(task.getSeverity())),
                    Map.of("escalationLevel", 2,
                            "timeoutMinutes",
                            EscalationTask.resolveTimeout(task.getSeverity()))
            );
        } else {
            log.info("Max escalation level reached: incidentId={}, tenant={}",
                    task.getIncidentId(), task.getTenantId());
        }
    }
}