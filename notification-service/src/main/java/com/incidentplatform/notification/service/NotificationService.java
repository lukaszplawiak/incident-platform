package com.incidentplatform.notification.service;

import com.incidentplatform.notification.channel.NotificationException;
import com.incidentplatform.notification.domain.NotificationQueueEntry;
import com.incidentplatform.notification.domain.UndeliverableReason;
import com.incidentplatform.notification.repository.NotificationLogRepository;
import com.incidentplatform.notification.repository.NotificationQueueRepository;
import com.incidentplatform.notification.router.NotificationRouter;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.incidentplatform.notification.router.NotificationEventTypes.INCIDENT_ESCALATED;
import static com.incidentplatform.notification.router.NotificationEventTypes.INCIDENT_OPENED;

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

    /**
     * Events that ask someone to act. Only these alert the operator when they
     * are undeliverable; for the others (acknowledged, resolved, closed) the
     * metric and the audit event are enough and an alert would only be noise.
     */
    private static final Set<String> ACTIONABLE_EVENTS =
            Set.of(INCIDENT_OPENED, INCIDENT_ESCALATED);

    private final NotificationRouter router;
    private final NotificationLogRepository logRepository;
    private final NotificationQueueRepository queueRepository;
    private final NotificationPersistenceService persistenceService;
    private final AuditEventPublisher auditEventPublisher;
    private final OperatorAlertService operatorAlertService;
    private final MeterRegistry meterRegistry;

    public NotificationService(NotificationRouter router,
                               NotificationLogRepository logRepository,
                               NotificationQueueRepository queueRepository,
                               NotificationPersistenceService persistenceService,
                               AuditEventPublisher auditEventPublisher,
                               OperatorAlertService operatorAlertService,
                               MeterRegistry meterRegistry) {
        this.router = router;
        this.logRepository = logRepository;
        this.queueRepository = queueRepository;
        this.persistenceService = persistenceService;
        this.auditEventPublisher = auditEventPublisher;
        this.operatorAlertService = operatorAlertService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Writes a PENDING outbox entry for the given incident event.
     *
     * <p>Called by the Kafka consumer. Must be fast — no external HTTP calls,
     * no channel routing, no oncall lookup. Just one DB INSERT.
     *
     * <p>Idempotent — if an entry already exists for this
     * {@code incidentId + tenantId + eventType + escalationLevel}
     * combination (e.g. Kafka redeliver), the existing entry is left
     * unchanged and this call is a no-op.
     *
     * <h2>Fixed: level-2 escalation notifications were dropped</h2>
     * The key used to be {@code incidentId + eventType} only. Every
     * escalation — level 1 (SECONDARY) and level 2 (MANAGER) — is an
     * {@code IncidentEscalatedEvent} for the same incident, so everything
     * after the first was discarded here as a "duplicate" and never sent.
     * {@code escalationLevel} is now part of the key; it is {@code 0} for
     * every event type that is not an escalation, so their behaviour is
     * unchanged. A repeat escalation at the <em>same</em> level is still
     * deduplicated on purpose.
     *
     * @param eventType       the incident lifecycle event type
     * @param incidentId      the incident UUID
     * @param tenantId        the tenant that owns the incident
     * @param severity        the incident severity at the time of the event
     * @param title           the incident title for notification content
     * @param escalationLevel the escalation level, {@code 0} if the event is
     *                        not an escalation
     * @param escalateTo      the user the escalation is addressed to, or
     *                        {@code null}; stored on the entry because the
     *                        recipient is resolved at send time
     */
    @Transactional
    public void enqueue(String eventType,
                        UUID incidentId,
                        String tenantId,
                        Severity severity,
                        String title,
                        int escalationLevel,
                        UUID escalateTo) {
        if (queueRepository
                .existsByIncidentIdAndTenantIdAndEventTypeAndEscalationLevel(
                        incidentId, tenantId, eventType, escalationLevel)) {
            log.debug("Notification already queued (idempotency): " +
                            "incidentId={}, eventType={}, escalationLevel={}",
                    incidentId, eventType, escalationLevel);
            return;
        }

        final NotificationQueueEntry entry = NotificationQueueEntry.pending(
                incidentId, tenantId, eventType, severity, title,
                escalationLevel, escalateTo);
        queueRepository.save(entry);

        log.info("Notification queued: incidentId={}, eventType={}, " +
                        "escalationLevel={}, tenant={}",
                incidentId, eventType, escalationLevel, tenantId);
    }

    /**
     * A channel is skipped when the on-call user has no address for it (for
     * example no phone, so no SMS for an escalation). It is never replaced by a
     * shared destination, so it must not be silent: a WARN and a
     * {@code notification.channel_skipped{channel,event_type}} count (no tenant tag).
     */
    private void reportSkippedChannels(List<String> skippedChannels,
                                       NotificationQueueEntry entry) {
        for (final String channel : skippedChannels) {
            log.warn("On-call user has no address for channel {} — skipped: " +
                            "incidentId={}, tenantId={}, eventType={}",
                    channel, entry.getIncidentId(), entry.getTenantId(),
                    entry.getEventType());
            meterRegistry.counter("notification.channel_skipped",
                    "channel", channel,
                    "event_type", entry.getEventType()).increment();
        }
    }

    /**
     * Parks {@code entry} as UNDELIVERABLE: nobody in the tenant could be
     * notified (backlog #0-18). Persists the state, counts it in
     * {@code notification.undeliverable{event_type,reason}} (no tenant tag:
     * that would explode the metric's cardinality), records an audit event for
     * the tenant, and for the events that need someone to act tells the
     * operator with a content-free alert.
     *
     * <p>Also called by {@code NotificationScheduler} when oncall-service has
     * been unavailable for longer than the retry window (backlog #0-19).
     */
    public void markUndeliverable(NotificationQueueEntry entry,
                                  UndeliverableReason reason) {
        final UUID incidentId = entry.getIncidentId();
        final String tenantId = entry.getTenantId();
        final String eventType = entry.getEventType();

        persistenceService.markUndeliverable(entry, reason);

        meterRegistry.counter("notification.undeliverable",
                "event_type", eventType,
                "reason", reason.name()).increment();

        auditEventPublisher.publishIncident(
                incidentId, tenantId,
                AuditEventTypes.NOTIFICATION_UNDELIVERABLE, SERVICE_NAME,
                "Notification undeliverable: nobody in the tenant could be "
                        + "notified (" + reason + ")",
                Map.of("eventType", eventType, "reason", reason.name()));

        if (ACTIONABLE_EVENTS.contains(eventType)) {
            operatorAlertService.undeliverable(
                    incidentId, tenantId, eventType, reason);
        }
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
        final int escalationLevel = entry.getEscalationLevel();

        // Ensure TenantContext is set — scheduler sets it per-entry but
        // this method may also be called directly in tests.
        TenantContext.set(tenantId);

        log.info("Processing notification queue entry: incidentId={}, " +
                        "eventType={}, tenant={}",
                incidentId, eventType, tenantId);

        // Resolve oncall and build channel requests — HTTP call to
        // oncall-service. Happens here (scheduler thread), with no
        // database transaction open (backlog #42).
        final var routing = router.route(
                eventType, incidentId, tenantId,
                entry.getSeverity(), entry.getTitle(), entry.getEscalateTo());

        reportSkippedChannels(routing.skippedChannels(), entry);

        // Backlog #0-18: nobody in the tenant can be notified. The incident text
        // is not sent anywhere else; the entry is parked and the operator told.
        if (routing.isUndeliverable()) {
            markUndeliverable(entry, routing.undeliverableReason());
            return;
        }

        final var channelRequests = routing.requests();

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
            if (logRepository
                    .existsByIncidentIdAndTenantIdAndEventTypeAndEscalationLevelAndChannel(
                            incidentId, tenantId, eventType, escalationLevel,
                            channel.channelName())) {
                log.info("Notification already sent (idempotency check): " +
                                "channel={}, incidentId={}, eventType={}, " +
                                "escalationLevel={}",
                        channel.channelName(), incidentId, eventType,
                        escalationLevel);
                continue;
            }

            try {
                channel.send(request);

                persistenceService.recordChannelSent(
                        incidentId, tenantId, eventType, escalationLevel,
                        channel.channelName(), request.recipient(),
                        request.subject(), request.message());

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
                        incidentId, tenantId, eventType, escalationLevel,
                        channel.channelName(), request.recipient(),
                        e.getMessage());

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
                        incidentId, tenantId, eventType, escalationLevel,
                        channel.channelName(), request.recipient(),
                        "Unexpected error: " + e.getMessage());

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