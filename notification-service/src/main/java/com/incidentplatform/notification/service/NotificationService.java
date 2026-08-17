package com.incidentplatform.notification.service;

import com.incidentplatform.notification.channel.NotificationException;
import com.incidentplatform.notification.domain.NotificationQueueEntry;
import com.incidentplatform.notification.repository.NotificationLogRepository;
import com.incidentplatform.notification.repository.NotificationQueueRepository;
import com.incidentplatform.notification.router.NotificationRouter;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Notification application service — two distinct responsibilities:
 *
 * <h2>1. Enqueue (called by Kafka consumer — fast path)</h2>
 * {@link #enqueue} writes a PENDING outbox entry to {@code notification_queue}
 * and returns immediately. The Kafka consumer acknowledges after this returns.
 * No HTTP calls, no external dependencies — just one DB INSERT. Still
 * {@code @Transactional} — a single fast operation with no external I/O
 * is exactly what that annotation is for; unlike {@link #processEntry}
 * below, there's nothing here to fix.
 *
 * <h2>2. Process (called by scheduler — slow path)</h2>
 * {@link #processEntry} reads a PENDING entry, resolves the current oncall
 * recipient via HTTP, sends notifications through each channel (Slack, Email,
 * SMS), and writes to the immutable {@code notification_log} audit trail.
 * This runs in a dedicated scheduled thread, completely decoupled from Kafka.
 *
 * <h2>Why recipient is resolved at process time, not enqueue time</h2>
 * The oncall schedule may rotate between enqueue and processing (typically
 * 30 seconds). Resolving at process time ensures the notification reaches
 * whoever is currently on duty — not the person who was on duty when the
 * Kafka event arrived.
 *
 * <h2>Fixed (backlog #42): {@code processEntry} no longer wraps external
 * I/O in an open transaction</h2>
 * See {@link NotificationPersistenceService}'s Javadoc for the full
 * account — {@code processEntry} previously carried {@code @Transactional}
 * on the whole method, including the oncall-service HTTP lookup and every
 * per-channel send (Slack/SMTP/SMS). Every database write below now goes
 * through {@code NotificationPersistenceService} instead, each in its own
 * short, independent transaction opened only after the corresponding
 * external call has already completed — matching the same fix already
 * applied to {@code EscalationScheduler} (backlog #39).
 */
@Service
public class NotificationService {

    private static final Logger log =
            LoggerFactory.getLogger(NotificationService.class);

    private static final String SERVICE_NAME = "notification-service";

    private final NotificationRouter router;
    private final NotificationLogRepository logRepository;
    private final NotificationQueueRepository queueRepository;
    private final NotificationPersistenceService persistenceService;
    private final AuditEventPublisher auditEventPublisher;

    public NotificationService(NotificationRouter router,
                               NotificationLogRepository logRepository,
                               NotificationQueueRepository queueRepository,
                               NotificationPersistenceService persistenceService,
                               AuditEventPublisher auditEventPublisher) {
        this.router = router;
        this.logRepository = logRepository;
        this.queueRepository = queueRepository;
        this.persistenceService = persistenceService;
        this.auditEventPublisher = auditEventPublisher;
    }

    /**
     * Writes a PENDING outbox entry for the given incident event.
     *
     * <p>Called by the Kafka consumer. Must be fast — no external HTTP calls,
     * no channel routing, no oncall lookup. Just one DB INSERT.
     *
     * <p>Idempotent — if an entry already exists for this
     * {@code incidentId + eventType} combination (e.g. Kafka redeliver),
     * the existing entry is left unchanged and this call is a no-op.
     *
     * @param eventType  the incident lifecycle event type
     * @param incidentId the incident UUID
     * @param tenantId   the tenant that owns the incident
     * @param severity   the incident severity at the time of the event
     * @param title      the incident title for notification content
     */
    @Transactional
    public void enqueue(String eventType,
                        UUID incidentId,
                        String tenantId,
                        Severity severity,
                        String title) {
        if (queueRepository.existsByIncidentIdAndEventType(incidentId, eventType)) {
            log.debug("Notification already queued (idempotency): " +
                            "incidentId={}, eventType={}",
                    incidentId, eventType);
            return;
        }

        final NotificationQueueEntry entry = NotificationQueueEntry.pending(
                incidentId, tenantId, eventType, severity, title);
        queueRepository.save(entry);

        log.info("Notification queued: incidentId={}, eventType={}, tenant={}",
                incidentId, eventType, tenantId);
    }

    /**
     * Processes a single PENDING outbox entry — resolves oncall, sends
     * notifications through each routed channel, writes audit log entries.
     *
     * <p>Called by {@code NotificationScheduler} in a dedicated scheduled
     * thread. May make HTTP calls to oncall-service, Slack API, SMTP, etc.
     * Never called from the Kafka consumer thread.
     *
     * <p>Marks the queue entry {@code SENT} if all channels were processed
     * (individual channel failures are logged to {@code notification_log}
     * with status FAILED but do not prevent other channels from being tried).
     * Marks {@code FAILED} only if an unexpected exception prevents processing
     * entirely — that happens in {@code NotificationScheduler}'s catch
     * block, not here, since this method no longer catches its own
     * top-level failures (removing {@code @Transactional} means there's no
     * longer a transaction boundary here to protect with a catch).
     *
     * @param entry the PENDING outbox entry to process
     */
    public void processEntry(NotificationQueueEntry entry) {
        final UUID incidentId = entry.getIncidentId();
        final String tenantId = entry.getTenantId();
        final String eventType = entry.getEventType();

        // Ensure TenantContext is set — scheduler sets it per-entry but
        // this method may also be called directly in tests.
        TenantContext.set(tenantId);

        log.info("Processing notification queue entry: incidentId={}, " +
                        "eventType={}, tenant={}",
                incidentId, eventType, tenantId);

        // Resolve oncall and build channel requests — HTTP call to
        // oncall-service. Happens here (scheduler thread), with no
        // database transaction open (backlog #42).
        final var channelRequests = router.route(
                eventType, incidentId, tenantId,
                entry.getSeverity(), entry.getTitle());

        if (channelRequests.isEmpty()) {
            log.debug("No channels configured for event: {}", eventType);
            persistenceService.markSent(entry);
            return;
        }

        for (final var channelRequest : channelRequests) {
            final var channel = channelRequest.channel();
            final var request = channelRequest.request();

            // Per-channel idempotency — skip if already sent for this event.
            // Guards against duplicate sends if the scheduler runs twice
            // before marking the entry as SENT. Plain read, outside any
            // transaction — gets its own short, auto-committing one from
            // Spring Data JPA, same as every other read-only repository
            // call in this codebase's schedulers.
            if (logRepository.existsByIncidentIdAndEventTypeAndChannel(
                    incidentId, eventType, channel.channelName())) {
                log.info("Notification already sent (idempotency check): " +
                                "channel={}, incidentId={}, eventType={}",
                        channel.channelName(), incidentId, eventType);
                continue;
            }

            try {
                channel.send(request);

                persistenceService.recordChannelSent(
                        incidentId, tenantId, eventType, channel.channelName(),
                        request.recipient(), request.subject(), request.message());

                log.info("Notification sent: channel={}, recipient={}, " +
                                "incidentId={}, tenant={}",
                        channel.channelName(), request.recipient(),
                        incidentId, tenantId);

                auditEventPublisher.publishIncident(
                        incidentId, tenantId,
                        AuditEventTypes.NOTIFICATION_SENT, SERVICE_NAME,
                        String.format("Notification sent via %s to %s",
                                channel.channelName(), request.recipient()),
                        Map.of("channel", channel.channelName(),
                                "recipient", request.recipient(),
                                "eventType", eventType)
                );

            } catch (NotificationException e) {
                persistenceService.recordChannelFailed(
                        incidentId, tenantId, eventType, channel.channelName(),
                        request.recipient(), e.getMessage());

                log.error("Notification failed: channel={}, recipient={}, " +
                                "incidentId={}, error={}",
                        channel.channelName(), request.recipient(),
                        incidentId, e.getMessage());

                auditEventPublisher.publishIncident(
                        incidentId, tenantId,
                        AuditEventTypes.NOTIFICATION_FAILED, SERVICE_NAME,
                        String.format("Notification failed via %s to %s: %s",
                                channel.channelName(), request.recipient(),
                                e.getMessage()),
                        Map.of("channel", channel.channelName(),
                                "recipient", request.recipient(),
                                "error", e.getMessage())
                );

            } catch (Exception e) {
                persistenceService.recordChannelFailed(
                        incidentId, tenantId, eventType, channel.channelName(),
                        request.recipient(), "Unexpected error: " + e.getMessage());

                log.error("Unexpected error sending notification: " +
                                "channel={}, incidentId={}",
                        channel.channelName(), incidentId, e);
            }
        }

        // Mark the queue entry as processed — all channels attempted.
        // Individual channel failures are recorded in notification_log
        // but do not prevent the entry from being marked SENT here.
        persistenceService.markSent(entry);

        log.info("Notification queue entry processed: eventType={}, " +
                        "channels={}, incidentId={}, tenant={}",
                eventType, channelRequests.size(), incidentId, tenantId);
    }
}