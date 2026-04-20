package com.incidentplatform.escalation.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.escalation.domain.EscalationTask;
import com.incidentplatform.escalation.repository.EscalationTaskRepository;
import com.incidentplatform.escalation.service.EscalationService;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.events.IncidentEscalatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
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

    private final EscalationTaskRepository taskRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final EscalationService escalationService;
    private final AuditEventPublisher auditEventPublisher;

    @Value("${kafka.topics.incidents-lifecycle}")
    private String incidentsLifecycleTopic;

    public EscalationScheduler(EscalationTaskRepository taskRepository,
                               KafkaTemplate<String, String> kafkaTemplate,
                               ObjectMapper objectMapper,
                               EscalationService escalationService,
                               AuditEventPublisher auditEventPublisher) {
        this.taskRepository = taskRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.escalationService = escalationService;
        this.auditEventPublisher = auditEventPublisher;
    }

    @Scheduled(
            fixedDelayString = "${escalation.scheduler-interval-ms:60000}",
            initialDelayString = "30000"
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
            try {
                escalate(task);
            } catch (Exception e) {
                log.error("Failed to escalate task: incidentId={}, " +
                        "error={}", task.getIncidentId(), e.getMessage(), e);
            }
        }
    }

    private void escalate(EscalationTask task) throws Exception {
        final IncidentEscalatedEvent event = new IncidentEscalatedEvent(
                task.getIncidentId(),
                task.getTenantId(),
                null,
                task.getEscalationLevel(),
                task.getSeverity(),
                task.getTitle(),
                Instant.now()
        );

        final String payload = objectMapper.writeValueAsString(event);

        kafkaTemplate.send(
                incidentsLifecycleTopic,
                task.getTenantId(),
                payload
        );

        task.markEscalated();
        taskRepository.save(task);

        log.info("Incident escalated: incidentId={}, tenant={}, " +
                        "severity={}, escalationLevel={}",
                task.getIncidentId(), task.getTenantId(),
                task.getSeverity(), task.getEscalationLevel());

        final String role = task.getEscalationLevel() == 1
                ? "SECONDARY" : "MANAGER";
        auditEventPublisher.publishSystem(
                task.getIncidentId(), task.getTenantId(),
                "ESCALATION_FIRED", SERVICE_NAME,
                String.format("Escalation level %d fired — %s notified. " +
                                "No ACK within timeout for severity %s.",
                        task.getEscalationLevel(), role, task.getSeverity()),
                Map.of("escalationLevel", task.getEscalationLevel(),
                        "role", role,
                        "severity", task.getSeverity())
        );

        // Layer 4 — consumer-side severity prioritization.
        // CRITICAL alerts logged at higher priority for faster identification.
        //
        // TODO: Split into separate topics per severity (alerts.raw.critical,
        // alerts.raw.high etc.) when project moves to Kubernetes with multiple replicas.
        // Priority benefit is minimal with single instance.
        // With multiple replicas — separate consumer groups with different concurrency:
        // alerts.raw.critical → concurrency=5
        // alerts.raw.high     → concurrency=3
        // alerts.raw.medium   → concurrency=2
        // alerts.raw.low      → concurrency=1

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
                    "ESCALATION_SCHEDULED", SERVICE_NAME,
                    String.format("Level 2 escalation scheduled — MANAGER " +
                                    "will be notified if no ACK within %d minutes.",
                            EscalationTask.resolveTimeout(task.getSeverity())),
                    Map.of("escalationLevel", 2,
                            "timeoutMinutes",
                            EscalationTask.resolveTimeout(task.getSeverity()))
            );
        } else {
            log.info("Max escalation level reached: incidentId={}, " +
                            "tenant={}", task.getIncidentId(),
                    task.getTenantId());
        }
    }
}