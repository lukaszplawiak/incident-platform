package com.incidentplatform.notification.scheduler;

import com.incidentplatform.notification.domain.NotificationQueueEntry;
import com.incidentplatform.notification.repository.NotificationQueueRepository;
import com.incidentplatform.notification.service.NotificationService;
import com.incidentplatform.shared.security.TenantContext;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Processes the notification outbox — picks up PENDING entries written by
 * the Kafka consumer and sends the actual notifications.
 *
 * <h2>Why this scheduler exists</h2>
 * The Kafka consumer ({@code IncidentEventConsumer}) writes a PENDING entry
 * to {@code notification_queue} and acknowledges immediately. This scheduler
 * is the only component that makes external HTTP calls (oncall-service, Slack,
 * email, SMS) — in a dedicated scheduled thread, completely decoupled from
 * Kafka consumer throughput.
 *
 * <h2>Pending threshold</h2>
 * Only entries older than {@code pendingThreshold} (default 30 seconds) are
 * processed. This prevents the scheduler from racing against the consumer —
 * a PENDING entry written 5 seconds ago by a consumer that just started is
 * left for the next scheduler run.
 *
 * <h2>ShedLock</h2>
 * Prevents concurrent execution across multiple notification-service instances.
 * Only one instance processes the outbox at a time — prevents duplicate
 * notifications.
 */
@Component
public class NotificationScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(NotificationScheduler.class);

    private final NotificationQueueRepository queueRepository;
    private final NotificationService notificationService;
    private final Duration pendingThreshold;

    public NotificationScheduler(
            NotificationQueueRepository queueRepository,
            NotificationService notificationService,
            @Value("${notification.scheduler.pending-threshold-seconds:30}")
            int pendingThresholdSeconds) {
        this.queueRepository = queueRepository;
        this.notificationService = notificationService;
        this.pendingThreshold = Duration.ofSeconds(pendingThresholdSeconds);
    }

    /**
     * Processes all PENDING outbox entries older than the pending threshold.
     *
     * <p>Each entry is processed independently — a failure on one entry
     * (e.g. oncall-service unavailable for one tenant) does not prevent
     * processing of other entries.
     *
     * <p>TenantContext is set per-entry and cleared in finally — no tenant
     * context leaks between entries even in the same scheduler run.
     */
    @Scheduled(
            fixedDelayString = "${notification.scheduler.interval-ms:30000}",
            initialDelayString = "30000"
    )
    @SchedulerLock(
            name = "notification-service:processPendingNotifications",
            lockAtMostFor = "4m",
            lockAtLeastFor = "10s"
    )
    public void processPendingNotifications() {
        final Instant threshold = Instant.now().minus(pendingThreshold);
        final List<NotificationQueueEntry> pending =
                queueRepository.findPendingOlderThan(threshold);

        if (pending.isEmpty()) {
            log.debug("Notification outbox: no PENDING entries to process");
            return;
        }

        log.info("Notification outbox: found {} PENDING entries to process",
                pending.size());

        for (final NotificationQueueEntry entry : pending) {
            TenantContext.set(entry.getTenantId());
            try {
                notificationService.processEntry(entry);
            } catch (Exception e) {
                log.error("Unexpected error processing notification queue entry: " +
                                "incidentId={}, eventType={}, error={}",
                        entry.getIncidentId(), entry.getEventType(),
                        e.getMessage(), e);

                try {
                    entry.markFailed(e.getMessage());
                    queueRepository.save(entry);
                } catch (Exception saveEx) {
                    log.error("Failed to mark queue entry as FAILED: " +
                                    "incidentId={}, error={}",
                            entry.getIncidentId(), saveEx.getMessage());
                }
            } finally {
                TenantContext.clear();
            }
        }
    }
}