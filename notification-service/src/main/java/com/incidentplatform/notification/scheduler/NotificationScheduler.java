package com.incidentplatform.notification.scheduler;

import com.incidentplatform.notification.config.NotificationSchedulerProperties;
import com.incidentplatform.notification.domain.NotificationQueueEntry;
import com.incidentplatform.notification.repository.NotificationQueueRepository;
import com.incidentplatform.notification.service.NotificationService;
import com.incidentplatform.notification.slack.SlackMessageStore;
import com.incidentplatform.shared.security.TenantContext;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
@EnableConfigurationProperties(NotificationSchedulerProperties.class)
public class NotificationScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(NotificationScheduler.class);

    private final NotificationQueueRepository queueRepository;
    private final NotificationService notificationService;
    private final SlackMessageStore messageStore;
    private final Duration pendingThreshold;
    private final Duration slackMessageTsRetention;

    public NotificationScheduler(
            NotificationQueueRepository queueRepository,
            NotificationService notificationService,
            SlackMessageStore messageStore,
            NotificationSchedulerProperties properties) {
        this.queueRepository = queueRepository;
        this.notificationService = notificationService;
        this.messageStore = messageStore;
        this.pendingThreshold = properties.pendingThreshold();
        this.slackMessageTsRetention = properties.slackMessageTsRetention();
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

    /**
     * Deletes {@code slack_message_ts} rows older than
     * {@code slackMessageTsRetention} (default 7 days).
     *
     * <p>Most rows are removed immediately after a successful Slack ACK
     * update ({@code SlackMessageStore.removeAllForIncident}, called from
     * {@code SlackActionService}). This job only catches the remainder:
     * incidents acknowledged some other way (e.g. the web UI instead of
     * the Slack button), whose rows would otherwise accumulate forever.
     *
     * <p>Runs once per hour by default — far less frequently than the
     * outbox processor, since this is pure housekeeping with no latency
     * requirement. Uses the same ShedLock instance name pattern as
     * {@link #processPendingNotifications} to prevent concurrent cleanup
     * across replicas (harmless if it did run concurrently — DELETE is
     * naturally idempotent — but avoids redundant work).
     */
    @Scheduled(
            fixedDelayString = "${notification.scheduler.slack-ts-cleanup-interval-ms:3600000}",
            initialDelayString = "60000"
    )
    @SchedulerLock(
            name = "notification-service:cleanupOldSlackMessageTs",
            lockAtMostFor = "4m",
            lockAtLeastFor = "10s"
    )
    public void cleanupOldSlackMessageTs() {
        final Instant threshold = Instant.now().minus(slackMessageTsRetention);
        final int deleted = messageStore.deleteOlderThan(threshold);

        if (deleted > 0) {
            log.info("Slack message ts cleanup: deleted {} entries older than {}",
                    deleted, slackMessageTsRetention);
        } else {
            log.debug("Slack message ts cleanup: nothing to delete");
        }
    }
}