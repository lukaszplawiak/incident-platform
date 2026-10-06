package com.incidentplatform.escalation.scheduler;

import com.incidentplatform.escalation.repository.EscalationTaskRepository;
import com.incidentplatform.shared.pause.PausableWork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * escalation-service's work for the pause of suspended tenants (backlog #0-82,
 * step 2b): the tenants with a PENDING escalation task, and, on resumption,
 * their timers moved on by the length of the pause
 * ({@link EscalationTaskRepository#resumePendingTimers}). The pause itself is
 * {@code shared}'s {@code PausedTenantsSync}; {@link EscalationScheduler}'s query
 * leaves a paused tenant's tasks out.
 */
@Component
public class EscalationPausableWork implements PausableWork {

    private static final Logger log = LoggerFactory.getLogger(EscalationPausableWork.class);

    private final EscalationTaskRepository taskRepository;

    public EscalationPausableWork(EscalationTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Override
    public List<String> tenantsWithPendingWork() {
        return taskRepository.findTenantsWithPendingTasks();
    }

    @Override
    public String pausedTable() {
        return EscalationTaskRepository.PAUSED_TABLE;
    }

    @Override
    public void onResume(String tenantId, Instant pausedAt) {
        final int moved = taskRepository.resumePendingTimers(tenantId, pausedAt);
        log.info("Escalation timers moved on by the pause on resumption: tenant={}, pausedAt={}, tasks={}",
                tenantId, pausedAt, moved);
    }
}
