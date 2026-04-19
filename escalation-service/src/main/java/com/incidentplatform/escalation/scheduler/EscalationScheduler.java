package com.incidentplatform.escalation.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.escalation.domain.EscalationTask;
import com.incidentplatform.escalation.repository.EscalationTaskRepository;
import com.incidentplatform.escalation.service.EscalationService;
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

@Component
public class EscalationScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(EscalationScheduler.class);

    private final EscalationTaskRepository taskRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final EscalationService escalationService;

    @Value("${kafka.topics.incidents-lifecycle}")
    private String incidentsLifecycleTopic;

    public EscalationScheduler(EscalationTaskRepository taskRepository,
                               KafkaTemplate<String, String> kafkaTemplate,
                               ObjectMapper objectMapper,
                               EscalationService escalationService) {
        this.taskRepository = taskRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.escalationService = escalationService;
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
        } else {
            log.info("Max escalation level reached: incidentId={}, " +
                            "tenant={}", task.getIncidentId(),
                    task.getTenantId());
        }
    }
}