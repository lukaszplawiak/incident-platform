package com.incidentplatform.notification.scheduler;

import com.incidentplatform.notification.repository.NotificationQueueRepository;
import com.incidentplatform.shared.pause.PausableWork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * notification-service's work for the pause of suspended tenants (backlog
 * #0-82, step 2b): the tenants with a PENDING queue entry, and, on resumption,
 * the routing lookups' retry windows started again
 * ({@link NotificationQueueRepository#restartLookupRetryWindows}). Nothing is
 * dropped for its age: the tenant's alerts were refused at intake while it was
 * suspended, so what waits is still true (decided in #0-82). The pause itself is
 * {@code shared}'s {@code PausedTenantsSync}; {@link NotificationScheduler}'s
 * query leaves a paused tenant's entries out.
 */
@Component
public class NotificationPausableWork implements PausableWork {

    private static final Logger log = LoggerFactory.getLogger(NotificationPausableWork.class);

    private final NotificationQueueRepository queueRepository;

    public NotificationPausableWork(NotificationQueueRepository queueRepository) {
        this.queueRepository = queueRepository;
    }

    @Override
    public List<String> tenantsWithPendingWork() {
        return queueRepository.findTenantsWithPendingEntries();
    }

    @Override
    public String pausedTable() {
        return NotificationQueueRepository.PAUSED_TABLE;
    }

    @Override
    public void onResume(String tenantId, Instant pausedAt) {
        final int restarted = queueRepository.restartLookupRetryWindows(tenantId);
        log.info("Notifications of a resumed tenant released: tenant={}, pausedAt={}, lookupWindowsRestarted={}",
                tenantId, pausedAt, restarted);
    }
}
