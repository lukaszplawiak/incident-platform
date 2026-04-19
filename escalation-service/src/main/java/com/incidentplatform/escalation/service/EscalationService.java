package com.incidentplatform.escalation.service;

import com.incidentplatform.escalation.domain.EscalationTask;
import com.incidentplatform.escalation.repository.EscalationTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class EscalationService {

    private static final Logger log =
            LoggerFactory.getLogger(EscalationService.class);

    private final EscalationTaskRepository taskRepository;

    public EscalationService(EscalationTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Transactional
    public void scheduleEscalation(UUID incidentId,
                                   String tenantId,
                                   Instant incidentOpenedAt,
                                   String severity,
                                   String title) {

        if (taskRepository.existsByIncidentIdAndEscalationLevel(
                incidentId, 1)) {
            log.debug("Level 1 escalation task already exists for " +
                    "incidentId={}, skipping", incidentId);
            return;
        }

        final EscalationTask task = EscalationTask.createLevel1(
                incidentId, tenantId, incidentOpenedAt, severity, title);

        taskRepository.save(task);

        log.info("Escalation level 1 scheduled: incidentId={}, tenant={}, " +
                        "severity={}, scheduledAt={}, timeoutMinutes={}",
                incidentId, tenantId, severity,
                task.getScheduledEscalationAt(),
                EscalationTask.resolveTimeout(severity));
    }

    @Transactional
    public void scheduleLevel2Escalation(UUID incidentId,
                                         String tenantId,
                                         String severity,
                                         String title) {

        if (taskRepository.existsByIncidentIdAndEscalationLevel(
                incidentId, 2)) {
            log.debug("Level 2 escalation task already exists for " +
                    "incidentId={}, skipping", incidentId);
            return;
        }

        final EscalationTask task = EscalationTask.createLevel2(
                incidentId, tenantId, Instant.now(), severity, title);

        taskRepository.save(task);

        log.info("Escalation level 2 scheduled: incidentId={}, tenant={}, " +
                        "severity={}, scheduledAt={}",
                incidentId, tenantId, severity,
                task.getScheduledEscalationAt());
    }

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
                taskRepository.save(task);
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