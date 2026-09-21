package com.incidentplatform.notification.scheduler;

import com.incidentplatform.notification.client.OncallLookupUnavailableException;
import com.incidentplatform.notification.config.NotificationSchedulerProperties;
import com.incidentplatform.notification.domain.NotificationQueueEntry;
import com.incidentplatform.notification.domain.UndeliverableReason;
import com.incidentplatform.notification.repository.NotificationQueueRepository;
import com.incidentplatform.notification.service.NotificationPersistenceService;
import com.incidentplatform.notification.service.NotificationService;
import com.incidentplatform.notification.slack.SlackMessageStore;
import com.incidentplatform.shared.security.TenantContext;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
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

    /** ShedLock {@code lockAtMostFor} of {@link #processPendingNotifications}; keep the two in step. */
    static final String LOCK_AT_MOST_FOR = "4m";
    static final Duration LOCK_AT_MOST_FOR_DURATION = Duration.ofMinutes(4);

    /** Room left for the entry in flight when the budget runs out. */
    private static final Duration LOCK_MARGIN = Duration.ofSeconds(30);

    private static final Logger log =
            LoggerFactory.getLogger(NotificationScheduler.class);

    private final NotificationQueueRepository queueRepository;
    private final NotificationService notificationService;
    private final NotificationPersistenceService persistenceService;
    private final SlackMessageStore messageStore;
    private final Duration pendingThreshold;
    private final Duration lookupRetryWindow;
    private final Duration processingBudget;
    private final int batchSize;
    private final Duration slackMessageTsRetention;

    public NotificationScheduler(
            NotificationQueueRepository queueRepository,
            NotificationService notificationService,
            NotificationPersistenceService persistenceService,
            SlackMessageStore messageStore,
            NotificationSchedulerProperties properties) {
        this.queueRepository = queueRepository;
        this.notificationService = notificationService;
        this.persistenceService = persistenceService;
        this.messageStore = messageStore;
        this.pendingThreshold = properties.pendingThreshold();
        this.lookupRetryWindow = properties.lookupRetryWindow();
        this.processingBudget = validated(properties.processingBudget());
        this.batchSize = properties.batchSize();
        this.slackMessageTsRetention = properties.slackMessageTsRetention();
    }

    /**
     * Fails at startup if the processing budget would let a run outlive the
     * ShedLock. A budget of 5 minutes, set through an environment variable, would
     * otherwise silently bring back the double processing across replicas that the
     * budget exists to prevent: another replica takes the lock while this run is
     * still walking the same PENDING entries.
     */
    private static Duration validated(Duration budget) {
        final Duration limit = LOCK_AT_MOST_FOR_DURATION.minus(LOCK_MARGIN);
        if (budget.isZero() || budget.isNegative() || budget.compareTo(limit) > 0) {
            throw new IllegalArgumentException(
                    "notification.scheduler.processing-budget must be positive and at most " +
                            limit + " (the lock duration " + LOCK_AT_MOST_FOR_DURATION +
                            " minus a " + LOCK_MARGIN + " margin for the entry in flight), was " +
                            budget);
        }
        return budget;
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
     *
     * <h2>Fixed (backlog #42): direct repository.save() in this catch
     * block</h2>
     * Previously called {@code queueRepository.save(entry)} directly here
     * — the one spot in this scheduler that broke the platform's
     * established convention (already followed by
     * {@code AuthEmailScheduler}, {@code PostmortemRetryScheduler}, and
     * {@code IncidentEventOutboxScheduler}) of never touching a
     * repository directly from a scheduler, always delegating through a
     * dedicated persistence service. Not a functional bug on its own —
     * this single call, outside any open transaction, got its own short
     * implicit one from Spring Data JPA regardless — but inconsistent
     * with how every other scheduler in this codebase handles the same
     * kind of write. Now delegates to
     * {@code NotificationPersistenceService.markFailed(...)}, wrapped in
     * the same defensive try/catch as before (a secondary failure
     * recording the failure must not prevent processing of the rest of
     * the batch — same reasoning as {@code EscalationScheduler}'s
     * {@code recordFailedAttemptSafely}, backlog #41).
     */
    @Scheduled(
            fixedDelayString = "${notification.scheduler.interval-ms:30000}",
            initialDelayString = "30000"
    )
    @SchedulerLock(
            name = "notification-service:processPendingNotifications",
            lockAtMostFor = NotificationScheduler.LOCK_AT_MOST_FOR,
            lockAtLeastFor = "10s"
    )
    public void processPendingNotifications() {
        final Instant threshold = Instant.now().minus(pendingThreshold);
        final List<NotificationQueueEntry> pending =
                queueRepository.findPendingOlderThan(threshold, PageRequest.of(0, batchSize));

        if (pending.isEmpty()) {
            log.debug("Notification outbox: no PENDING entries to process");
            return;
        }

        log.info("Notification outbox: found {} PENDING entries to process",
                pending.size());

        // One run must finish inside the ShedLock (lockAtMostFor = 4m): a run that
        // outlives it lets a second replica start on the same PENDING entries. So a
        // run stops after processingBudget and leaves the rest for the next cycle,
        // but always processes at least one entry so it can never stall on the budget.
        final Instant deadline = Instant.now().plus(processingBudget);
        int processed = 0;
        int leftPending = 0;

        for (final NotificationQueueEntry entry : pending) {
            if (processed > 0 && Instant.now().isAfter(deadline)) {
                log.warn("Notification outbox: processing budget of {} used up — " +
                                "{} of {} entries are left for the next cycle",
                        processingBudget, pending.size() - processed, pending.size());
                break;
            }
            processed++;

            TenantContext.set(entry.getTenantId());
            try {
                notificationService.processEntry(entry);
            } catch (OncallLookupUnavailableException e) {
                if (handleLookupUnavailable(entry, e)) {
                    leftPending++;
                }
            } catch (Exception e) {
                log.error("Unexpected error processing notification queue entry: " +
                                "incidentId={}, eventType={}, error={}",
                        entry.getIncidentId(), entry.getEventType(),
                        e.getMessage(), e);

                try {
                    persistenceService.markFailed(entry, e.getMessage());
                } catch (Exception markFailedEx) {
                    log.error("Failed to mark queue entry as FAILED: " +
                                    "incidentId={}, error={}",
                            entry.getIncidentId(), markFailedEx.getMessage());
                }
            } finally {
                TenantContext.clear();
            }
        }

        if (leftPending > 0) {
            log.warn("Notification outbox: oncall-service is unavailable — {} " +
                            "entries stay PENDING and are retried for up to {} " +
                            "from their first failed lookup",
                    leftPending, lookupRetryWindow);
        }
    }

    /**
     * Backlog #0-19: oncall-service could not answer the lookup that decides who
     * is notified. The lookup runs before anything is sent, so the entry is
     * untouched: it stays PENDING and the next cycle tries it again, until the
     * lookup has been failing for {@code lookupRetryWindow}. After that it is
     * parked as UNDELIVERABLE and the operator is told.
     *
     * <p>The window runs from the entry's <em>first failed lookup</em>, not from
     * its creation. Measured from creation, an entry that had already waited
     * longer than the window (a restart, a long outage) was parked by a single
     * transient failure, and a whole backlog at once: the escalation denial this
     * item set out to remove, with a longer horizon.
     *
     * <p>Before #0-19, the outage was read as "nobody on call": the entry was
     * marked SENT and never retried.
     *
     * @return true if the entry was left PENDING for another attempt
     */
    private boolean handleLookupUnavailable(NotificationQueueEntry entry,
                                            OncallLookupUnavailableException cause) {
        // Only the first failure is recorded; the entry loaded on later cycles already
        // has it, and writing again would cost a SELECT and a transaction per entry
        // per cycle for nothing.
        if (entry.getFirstLookupFailureAt() == null) {
            try {
                persistenceService.recordLookupFailure(entry);
            } catch (Exception e) {
                // Not recorded: the next cycle records it again. That only ever makes
                // the window longer, never shorter.
                log.error("Failed to record the failed lookup on the queue entry: " +
                                "incidentId={}, error={}",
                        entry.getIncidentId(), e.getMessage());
            }
        }

        final Instant since = entry.getFirstLookupFailureAt() != null
                ? entry.getFirstLookupFailureAt() : Instant.now();
        final Duration failingFor = Duration.between(since, Instant.now());

        if (failingFor.compareTo(lookupRetryWindow) < 0) {
            log.debug("oncall-service unavailable — entry stays PENDING: " +
                            "incidentId={}, eventType={}, failingFor={}, error={}",
                    entry.getIncidentId(), entry.getEventType(), failingFor,
                    cause.getMessage());
            return true;
        }

        log.error("oncall-service still unavailable after the retry window — " +
                        "giving up: incidentId={}, eventType={}, failingFor={}",
                entry.getIncidentId(), entry.getEventType(), failingFor);

        try {
            notificationService.markUndeliverable(
                    entry, UndeliverableReason.ONCALL_UNAVAILABLE);
        } catch (Exception e) {
            log.error("Failed to mark queue entry as UNDELIVERABLE: " +
                            "incidentId={}, error={}",
                    entry.getIncidentId(), e.getMessage());
        }
        return false;
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