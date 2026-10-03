package com.incidentplatform.incident.scheduler;

import com.incidentplatform.incident.config.IncidentEventOutboxProperties;
import com.incidentplatform.incident.domain.IncidentEventOutbox;
import com.incidentplatform.incident.repository.IncidentEventOutboxRepository;
import com.incidentplatform.incident.service.IncidentEventOutboxPersistenceService;
import com.incidentplatform.shared.events.IncidentEventKafkaSender;
import com.incidentplatform.shared.security.TenantContext;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Publishes {@link IncidentEventOutbox} PENDING entries to Kafka — the
 * other half of backlog #36's fix, alongside
 * {@code IncidentEventPublisher} (which writes the PENDING entries this
 * class reads).
 *
 * <h2>Why a short poll interval</h2>
 * Unlike {@code AuthEmailScheduler} (30s for invite/reset emails — a human
 * clicking a link tolerates a short delay fine), incident lifecycle events
 * drive real-time platform behavior: escalation-service's timeout clocks
 * and notification-service's paging both start counting from when they
 * receive {@code IncidentOpenedEvent}. The poll interval
 * (default {@value IncidentEventOutboxProperties#DEFAULT_POLL_INTERVAL_MS}ms)
 * is the only latency this pattern adds over the old direct-publish
 * behavior, and is kept short specifically to keep that overhead
 * negligible relative to human/escalation-timer timescales.
 *
 * <h2>Why {@code sendRawSync}, not {@code send}</h2>
 * This scheduler needs to know definitively whether each entry's publish
 * succeeded before deciding PUBLISHED vs. leave-PENDING-for-retry — the
 * async, fire-and-forget {@code IncidentEventKafkaSender.send} can't
 * provide that synchronously. Blocking here is safe: this runs on its own
 * dedicated scheduled thread, not an HTTP request thread, so there's no
 * user-facing latency to protect (see {@code sendRawSync}'s own Javadoc).
 *
 * <h2>ShedLock</h2>
 * Prevents duplicate publishing if incident-service is horizontally
 * scaled — only one instance processes a given poll cycle's batch.
 *
 * <h2>Fixed (backlog #42): missing TenantContext per entry</h2>
 * {@code AuthEmailScheduler}, {@code PostmortemRetryScheduler}, and
 * {@code EscalationScheduler} all set {@link TenantContext} for the
 * duration of processing each individual item and clear it in
 * {@code finally}, so every log line — including ones emitted deep
 * inside {@code IncidentEventKafkaSender} — automatically carries the
 * correct tenantId in MDC. This class was the one gap: every log line
 * below previously had to pass {@code tenant={}} explicitly as a
 * parameter instead of getting it for free from MDC, and any future
 * logging added deeper in the call chain (e.g. inside
 * {@code IncidentEventKafkaSender.sendRawSync}) wouldn't have picked up
 * the tenant at all. {@link IncidentEventOutbox} already carries
 * {@code tenantId} per entry, so nothing else needed to change to fix
 * this — just set and clear it around {@link #processOne}.
 *
 * <h2>One entry never stops the batch (backlog #0-92)</h2>
 * {@link TenantContext#set} refuses a tenant id that is not a slug, and it
 * used to run before the {@code try}: one such row, oldest first, ended every
 * poll before any later entry was sent, for every tenant (found in review).
 * The set is now inside {@link #processOne}'s {@code try}, so a refused
 * tenant is recorded on the entry ({@code markFailed}, once) like a failed
 * send. Anything that escapes it ({@code markFailed} itself failing) is logged
 * and the loop goes on, without a second {@code markFailed} against the same
 * failing database (found in review). Such a row cannot be written any more
 * (V15's CHECK on {@code tenant_id}). The entry stays PENDING and first in
 * line, as every entry that keeps failing does (see
 * {@code IncidentEventOutboxStatus}: nothing here gives up); the CHECK is what
 * keeps a refused tenant from being one of them.
 */
@Component
@EnableConfigurationProperties(IncidentEventOutboxProperties.class)
public class IncidentEventOutboxScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentEventOutboxScheduler.class);

    private final IncidentEventOutboxRepository outboxRepository;
    private final IncidentEventKafkaSender kafkaSender;
    private final IncidentEventOutboxPersistenceService persistenceService;
    private final IncidentEventOutboxProperties properties;

    public IncidentEventOutboxScheduler(
            IncidentEventOutboxRepository outboxRepository,
            IncidentEventKafkaSender kafkaSender,
            IncidentEventOutboxPersistenceService persistenceService,
            IncidentEventOutboxProperties properties) {
        this.outboxRepository = outboxRepository;
        this.kafkaSender = kafkaSender;
        this.persistenceService = persistenceService;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${incident.event-outbox.poll-interval-ms:2000}",
            initialDelayString = "5000"
    )
    @SchedulerLock(
            name = "incident-service:processIncidentEventOutbox",
            lockAtMostFor = "2m",
            lockAtLeastFor = "1s"
    )
    public void processPending() {
        final List<IncidentEventOutbox> pending =
                outboxRepository.findPendingOrderByCreatedAt(
                        PageRequest.of(0, properties.batchSize()));

        if (pending.isEmpty()) {
            return;
        }

        log.debug("Incident event outbox: processing {} PENDING entries",
                pending.size());

        for (final IncidentEventOutbox entry : pending) {
            // Fixed (backlog #42): see this class's own Javadoc for the
            // full account — matches the same per-entry
            // set/try/finally-clear pattern already used by every other
            // scheduler in this codebase. The set is inside processOne's
            // try (see "One entry never stops the batch" in the class
            // Javadoc).
            try {
                processOne(entry);
            } catch (RuntimeException e) {
                // Only markFailed itself failing reaches here: logged, not
                // recorded again (the same database would fail again, and
                // each attempt waits for a connection; found in review).
                log.error("Incident event outbox entry could not be processed, "
                                + "the rest of the batch goes on: entryId={}, eventType={}, error={}",
                        entry.getId(), entry.getEventType(), e.toString());
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void processOne(IncidentEventOutbox entry) {
        try {
            // Inside the try: a tenant TenantContext refuses is this entry's
            // failed attempt, recorded once like a failed send (backlog #0-92).
            TenantContext.set(entry.getTenantId());
            kafkaSender.sendRawSync(
                    entry.getIncidentId().toString(),
                    entry.getTenantId(),
                    entry.getEventType(),
                    entry.getPayload(),
                    properties.sendTimeout());

            persistenceService.markPublished(entry.getId());

            log.info("Incident event published from outbox: eventType={}, " +
                            "incidentId={}, tenant={}, attempt={}",
                    entry.getEventType(), entry.getIncidentId(),
                    entry.getTenantId(), entry.getRetryCount() + 1);

        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                // Restored, not swallowed: the scheduler is being stopped
                // (found in review). The attempt is still recorded.
                Thread.currentThread().interrupt();
            }
            // No permanent-failure branch — see IncidentEventOutboxStatus's
            // Javadoc for why every failure here is left PENDING for the
            // next poll cycle to retry, indefinitely.
            persistenceService.markFailed(entry.getId(), e.getMessage());

            log.warn("Failed to publish incident event from outbox — " +
                            "will retry next cycle: eventType={}, " +
                            "incidentId={}, tenant={}, attempt={}, error={}",
                    entry.getEventType(), entry.getIncidentId(),
                    entry.getTenantId(), entry.getRetryCount() + 1,
                    e.getMessage());
        }
    }
}