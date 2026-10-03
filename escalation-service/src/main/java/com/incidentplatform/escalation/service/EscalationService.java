package com.incidentplatform.escalation.service;

import com.incidentplatform.escalation.domain.EscalationTask;
import com.incidentplatform.escalation.repository.EscalationTaskRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.domain.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class EscalationService {

    private static final Logger log =
            LoggerFactory.getLogger(EscalationService.class);

    private static final String SERVICE_NAME = "escalation-service";

    private final EscalationTaskRepository taskRepository;
    private final AuditEventPublisher auditEventPublisher;

    public EscalationService(EscalationTaskRepository taskRepository,
                             AuditEventPublisher auditEventPublisher) {
        this.taskRepository = taskRepository;
        this.auditEventPublisher = auditEventPublisher;
    }

    @Transactional
    public void scheduleEscalation(UUID incidentId,
                                   String tenantId,
                                   UUID teamId,
                                   Instant incidentOpenedAt,
                                   Severity severity,
                                   String title) {

        if (taskRepository.existsByIncidentIdAndEscalationLevel(
                incidentId, 1)) {
            log.debug("Level 1 escalation task already exists for " +
                    "incidentId={}, skipping", incidentId);
            return;
        }

        final EscalationTask task = EscalationTask.createLevel1(
                incidentId, tenantId, teamId, incidentOpenedAt, severity, title);

        taskRepository.save(task);

        log.info("Escalation level 1 scheduled: incidentId={}, tenant={}, " +
                        "severity={}, scheduledAt={}, timeoutMinutes={}",
                incidentId, tenantId, severity,
                task.getScheduledEscalationAt(),
                EscalationTask.resolveTimeout(severity));
    }

    /**
     * Schedules the level-2 (MANAGER) escalation, with its
     * {@code ESCALATION_SCHEDULED} audit event written to the audit outbox in
     * the same transaction (backlog #0-84): the task and the event commit
     * together or not at all. Before, the scheduler sent the event after this
     * method returned, also when it had only found an existing task.
     */
    @Transactional
    public void scheduleLevel2Escalation(UUID incidentId,
                                         String tenantId,
                                         UUID teamId,
                                         Severity severity,
                                         String title) {

        if (taskRepository.existsByIncidentIdAndEscalationLevel(
                incidentId, 2)) {
            log.debug("Level 2 escalation task already exists for " +
                    "incidentId={}, skipping", incidentId);
            return;
        }

        final EscalationTask task = EscalationTask.createLevel2(
                incidentId, tenantId, teamId, Instant.now(), severity, title);

        taskRepository.save(task);

        final int timeoutMinutes = EscalationTask.resolveTimeout(severity);
        auditEventPublisher.publishIncident(
                incidentId, tenantId,
                AuditEventTypes.ESCALATION_SCHEDULED, SERVICE_NAME,
                String.format("Level 2 escalation scheduled — MANAGER " +
                        "will be notified if no ACK within %d minutes.", timeoutMinutes),
                Map.of("escalationLevel", 2,
                        "timeoutMinutes", timeoutMinutes));

        log.info("Escalation level 2 scheduled: incidentId={}, tenant={}, " +
                        "severity={}, scheduledAt={}",
                incidentId, tenantId, severity,
                task.getScheduledEscalationAt());
    }

    /**
     * <h2>Fixed (backlog #38): saveAndFlush(), not save()</h2>
     * See {@link EscalationTask}'s own Javadoc for the full account of the
     * race this guards against — this is the other side of it. If
     * {@code EscalationScheduler.checkAndEscalate()} concurrently
     * escalated one of these tasks (its own scheduled thread, running
     * independently of this Kafka-listener-thread call), that task's
     * version will have changed since it was read here, and
     * {@code saveAndFlush} surfaces that as an
     * {@link org.springframework.dao.OptimisticLockingFailureException}
     * immediately — letting this method's caller
     * ({@code IncidentEventConsumer}) treat it as a transient error and
     * {@code nack} the event to read it again (backlog #0-96;
     * {@code KafkaFailures}), rather than silently overwriting
     * whatever the scheduler already committed.
     */
    @Transactional
    public void cancelEscalation(UUID incidentId, String tenantId) {
        final var tasks = taskRepository.findAllByIncidentId(incidentId);

        if (tasks.isEmpty()) {
            log.debug("No escalation tasks found for incidentId={}, " +
                    "nothing to cancel", incidentId);
            return;
        }

        int cancelled = 0;
        for (final var task : tasks) {
            if (task.isPending()) {
                task.cancel();
                taskRepository.saveAndFlush(task);
                cancelled++;
            }
        }

        if (cancelled > 0) {
            log.info("Escalation cancelled (ACK received): incidentId={}, " +
                            "tenant={}, cancelledTasks={}",
                    incidentId, tenantId, cancelled);
        }
    }
}