package com.incidentplatform.incident.scheduler;

import com.incidentplatform.incident.config.IncidentEventOutboxProperties;
import com.incidentplatform.incident.domain.IncidentEventOutbox;
import com.incidentplatform.incident.repository.IncidentEventOutboxRepository;
import com.incidentplatform.incident.service.IncidentEventOutboxPersistenceService;
import com.incidentplatform.shared.events.IncidentEventKafkaSender;
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
            processOne(entry);
        }
    }

    private void processOne(IncidentEventOutbox entry) {
        try {
            kafkaSender.sendRawSync(
                    entry.getIncidentId().toString(),
                    entry.getEventType(),
                    entry.getPayload(),
                    properties.sendTimeout());

            persistenceService.markPublished(entry.getId());

            log.info("Incident event published from outbox: eventType={}, " +
                            "incidentId={}, tenant={}, attempt={}",
                    entry.getEventType(), entry.getIncidentId(),
                    entry.getTenantId(), entry.getRetryCount() + 1);

        } catch (Exception e) {
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