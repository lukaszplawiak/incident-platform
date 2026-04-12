package com.incidentplatform.escalation.service;

import com.incidentplatform.escalation.domain.EscalationTask;
import com.incidentplatform.escalation.repository.EscalationTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class EscalationService {

    private static final Logger log =
            LoggerFactory.getLogger(EscalationService.class);

    private final EscalationTaskRepository taskRepository;

    @Value("${escalation.threshold-minutes:15}")
    private int thresholdMinutes;

    public EscalationService(EscalationTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Transactional
    public void scheduleEscalation(UUID incidentId,
                                   String tenantId,
                                   Instant incidentOpenedAt,
                                   String severity,
                                   String title) {

        if (taskRepository.existsByIncidentId(incidentId)) {
            log.debug("Escalation task already exists for incidentId={}, " +
                    "skipping", incidentId);
            return;
        }

        final EscalationTask task = EscalationTask.create(
                incidentId, tenantId, incidentOpenedAt,
                thresholdMinutes, severity, title);

        taskRepository.save(task);

        log.info("Escalation scheduled: incidentId={}, tenant={}, " +
                        "scheduledAt={}, thresholdMinutes={}",
                incidentId, tenantId,
                task.getScheduledEscalationAt(), thresholdMinutes);
    }

    @Transactional
    public void cancelEscalation(UUID incidentId, String tenantId) {
        taskRepository.findByIncidentId(incidentId).ifPresentOrElse(
                task -> {
                    if (task.isPending()) {
                        task.cancel();
                        taskRepository.save(task);
                        log.info("Escalation cancelled (ACK received): " +
                                        "incidentId={}, tenant={}",
                                incidentId, tenantId);
                    } else {
                        log.debug("Escalation task not PENDING " +
                                        "(status={}), skipping cancel: incidentId={}",
                                task.getStatus(), incidentId);
                    }
                },
                () -> log.debug("No escalation task found for incidentId={}, " +
                        "nothing to cancel", incidentId)
        );
    }
}