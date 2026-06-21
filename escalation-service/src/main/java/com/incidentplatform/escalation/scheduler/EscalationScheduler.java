package com.incidentplatform.escalation.scheduler;

import com.incidentplatform.escalation.domain.EscalationTask;
import com.incidentplatform.escalation.repository.EscalationTaskRepository;
import com.incidentplatform.escalation.service.EscalationService;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.events.IncidentEscalatedEvent;
import com.incidentplatform.shared.events.IncidentEventKafkaSender;
import com.incidentplatform.shared.events.IncidentEventTypes;
import com.incidentplatform.shared.security.TenantContext;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class EscalationScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(EscalationScheduler.class);

    private static final String SERVICE_NAME = "escalation-service";

    private static final String ESCALATION_ROLE_LEVEL_1 = "SECONDARY";
    private static final String ESCALATION_ROLE_LEVEL_2 = "MANAGER";

    private final EscalationTaskRepository taskRepository;
    private final IncidentEventKafkaSender kafkaSender;
    private final EscalationService escalationService;
    private final AuditEventPublisher auditEventPublisher;

    public EscalationScheduler(
            EscalationTaskRepository taskRepository,
            IncidentEventKafkaSender kafkaSender,
            EscalationService escalationService,
            AuditEventPublisher auditEventPublisher) {
        this.taskRepository = taskRepository;
        this.kafkaSender = kafkaSender;
        this.escalationService = escalationService;
        this.auditEventPublisher = auditEventPublisher;
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
    @Transactional
    public void checkAndEscalate() {
        final List<EscalationTask> dueTasks =
                taskRepository.findDueForEscalation(Instant.now());

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
        final IncidentEscalatedEvent event = new IncidentEscalatedEvent(
                task.getIncidentId(),
                task.getTenantId(),
                null,
                task.getEscalationLevel(),
                task.getSeverity(),
                task.getTitle(),
                Instant.now()
        );

        // ── Ordering: persist state BEFORE publishing to Kafka ───────────────
        //
        // task.markEscalated() + taskRepository.save() happen first so that if
        // kafkaSender.send() throws afterwards, the @Transactional context on
        // checkAndEscalate() has already committed the ESCALATED status to the
        // database (save() is called inside the same transaction).
        // findDueForEscalation() will therefore NOT return this task again on the
        // next scheduler tick — preventing duplicate notifications (double SMS /
        // email / Slack) to the on-call engineer.
        //
        // Trade-off — at-most-once Kafka delivery:
        // If the process crashes between save() and kafkaSender.send(), the task
        // is marked ESCALATED in the DB but the Kafka event was never sent —
        // the on-call engineer will not be notified for this escalation level.
        //
        // TODO: For true exactly-once delivery, replace the direct kafkaSender.send()
        //  call with the Transactional Outbox Pattern:
        //  1. Persist an OutboxEvent row (same DB transaction as markEscalated()).
        //  2. A separate OutboxEventRelay scheduler polls PENDING outbox rows,
        //     sends them to Kafka, then marks them SENT.
        //  This guarantees that state change and Kafka publish either both happen
        //  or neither does, even across process crashes.
        //  Cost: ~5 new classes (OutboxEvent, OutboxEventRepository,
        //  OutboxEventRelay, OutboxEventStatus, Flyway migration) + idempotent
        //  consumer in notification-service to handle relay-induced at-least-once.
        //  Justified when running multiple instances (Kubernetes HPA) or when
        //  duplicate on-call notifications have business/regulatory consequences.
        // ────────────────────────────────────────────────────────────────────

        task.markEscalated();
        taskRepository.save(task);

        // Publishes to incidents-lifecycle with X-Event-Type header so that
        // notification-service routes this event to EMAIL/SLACK/SMS.
        kafkaSender.send(event, IncidentEventTypes.INCIDENT_ESCALATED);

        log.info("Incident escalated: incidentId={}, tenant={}, " +
                        "severity={}, escalationLevel={}",
                task.getIncidentId(), task.getTenantId(),
                task.getSeverity(), task.getEscalationLevel());

        final String role = task.getEscalationLevel() == 1
                ? ESCALATION_ROLE_LEVEL_1 : ESCALATION_ROLE_LEVEL_2;

        auditEventPublisher.publishSystem(
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
                    task.getSeverity(),
                    task.getTitle()
            );

            log.info("Level 2 escalation scheduled: incidentId={}, " +
                            "tenant={}, severity={}",
                    task.getIncidentId(), task.getTenantId(),
                    task.getSeverity());

            auditEventPublisher.publishSystem(
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