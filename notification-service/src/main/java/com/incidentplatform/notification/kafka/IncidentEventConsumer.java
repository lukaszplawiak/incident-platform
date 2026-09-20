package com.incidentplatform.notification.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.incidentplatform.notification.service.NotificationService;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.events.IncidentEventTypes;
import com.incidentplatform.shared.kafka.DeadLetterPublisher;
import com.incidentplatform.shared.kafka.TenantKafkaRecordResolver;
import com.incidentplatform.shared.kafka.UnrecognizedSeverityException;
import com.incidentplatform.shared.security.TenantContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class IncidentEventConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentEventConsumer.class);

    /**
     * Highest valid {@code IncidentEscalatedEvent.escalationLevel}.
     * escalation-service's {@code EscalationTask} only ever creates level 1
     * (SECONDARY) and level 2 (MANAGER). The bound exists because the level
     * is part of the notification idempotency key — see
     * {@link #extractEscalationLevel}. A future level 3 must raise this
     * constant together with the escalation chain; until then it fails loudly
     * (dead-letter topic plus an error log) instead of being silently
     * mis-keyed.
     */
    private static final int MAX_ESCALATION_LEVEL = 2;

    /**
     * Outbox Pattern — this consumer only enqueues, never sends.
     * All HTTP calls (oncall-service, Slack, Email, SMS) happen in
     * {@code NotificationScheduler}, not here.
     */
    private final NotificationService notificationService;
    private final DeadLetterPublisher deadLetterPublisher;
    private final TenantKafkaRecordResolver tenantRecordResolver;

    public IncidentEventConsumer(NotificationService notificationService,
                                 DeadLetterPublisher deadLetterPublisher,
                                 TenantKafkaRecordResolver tenantRecordResolver) {
        this.notificationService = notificationService;
        this.deadLetterPublisher = deadLetterPublisher;
        this.tenantRecordResolver = tenantRecordResolver;
    }

    @KafkaListener(
            topics = "${kafka.topics.incidents-lifecycle}",
            groupId = "notification-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeIncidentEvent(ConsumerRecord<String, String> record,
                                     Acknowledgment acknowledgment) {
        log.debug("Received incident event: topic={}, partition={}, offset={}",
                record.topic(), record.partition(), record.offset());

        // TenantContext is pre-initialised so that finally { TenantContext.clear() }
        // is always safe, even if extractTenantId() throws before setting it.
        TenantContext.set("unknown");

        try {
            // Read eventType from Kafka header set by IncidentEventPublisher.
            // Header-based routing is explicit and stable — producers declare
            // the type, consumers don't need to guess from payload structure.
            final String eventType = extractEventType(record);
            if (eventType == null) {
                log.error("Missing {} header — skipping: topic={}, partition={}, offset={}",
                        IncidentEventTypes.HEADER_NAME,
                        record.topic(), record.partition(), record.offset());
                acknowledgment.acknowledge();
                return;
            }

            final JsonNode event = tenantRecordResolver.parseJson(record.value());
            final String tenantId = tenantRecordResolver.extractTenantId(record, event);
            TenantContext.set(tenantId);

            final UUID incidentId = UUID.fromString(
                    event.get("incidentId").asText());

            final Severity severity = parseSeverity(
                    event.path("severity").asText(), incidentId);

            final String title = event.path("title").asText("Unknown incident");

            // Only IncidentEscalatedEvent carries an escalation level and
            // target. Level 0 = "not an escalation" keeps every other event
            // type keyed exactly as before.
            final boolean escalation =
                    IncidentEventTypes.INCIDENT_ESCALATED.equals(eventType);
            final int escalationLevel = escalation
                    ? extractEscalationLevel(event) : 0;
            final UUID escalateTo = escalation
                    ? extractEscalateTo(event, incidentId) : null;

            log.info("Processing incident event: type={}, incidentId={}, " +
                            "severity={}, escalationLevel={}, tenant={}",
                    eventType, incidentId, severity, escalationLevel, tenantId);

            // Outbox Pattern: write PENDING entry and acknowledge immediately.
            // NotificationScheduler sends actual notifications asynchronously.
            notificationService.enqueue(
                    eventType, incidentId, tenantId, severity, title,
                    escalationLevel, escalateTo);

        } catch (UnrecognizedSeverityException e) {
            // Poison pill — unrecognized severity cannot be fixed by retrying.
            // Route to DLT, then acknowledge to unblock the partition.
            final String tenantId = TenantContext.getOrNull();
            log.error("Poison pill (unrecognized severity) — routing to DLT: " +
                            "topic={}, partition={}, offset={}, tenant={}, error={}",
                    record.topic(), record.partition(), record.offset(),
                    tenantId, e.getMessage());

            deadLetterPublisher.publish(
                    record.value(),
                    record.topic(),
                    tenantId != null ? tenantId : "unknown",
                    e.getMessage());
            acknowledgment.acknowledge();
            return;

        } catch (IllegalArgumentException e) {
            // Poison pill — unparseable JSON, missing tenantId, bad UUID, or
            // missing required field. Retrying will never succeed. Route to
            // DLT, then acknowledge to unblock the partition.
            final String tenantId = TenantContext.getOrNull();
            log.error("Poison pill detected — routing to DLT: " +
                            "topic={}, partition={}, offset={}, tenant={}, error={}",
                    record.topic(), record.partition(), record.offset(),
                    tenantId, e.getMessage());

            deadLetterPublisher.publish(
                    record.value(),
                    record.topic(),
                    tenantId != null ? tenantId : "unknown",
                    e.getMessage());
            acknowledgment.acknowledge();
            return;

        } catch (Exception e) {
            // Transient error — most likely DB unavailable during outbox INSERT.
            // Do NOT acknowledge — Kafka will redeliver after DB recovers.
            // No notification is lost because the outbox entry was not written.
            log.error("Transient error processing incident event — " +
                            "will be redelivered: topic={}, partition={}, " +
                            "offset={}, error={}",
                    record.topic(), record.partition(),
                    record.offset(), e.getMessage(), e);
            return;

        } finally {
            TenantContext.clear();
        }

        // Reached only on success — all error paths return early above.
        acknowledgment.acknowledge();
    }

    /**
     * Reads and validates the {@code escalationLevel} of an
     * {@code IncidentEscalatedEvent}.
     *
     * <p>Unlike {@code escalateTo}, the level is <em>required</em>: it is part
     * of the notification idempotency key, so it must be trustworthy. The old
     * {@code asInt(0)} turned a missing, null or non-numeric value into
     * {@code 0} — the "not an escalation" level — which made every malformed
     * escalation of an incident collapse onto one key and silently discard all
     * but the first (the very bug the level was introduced to fix). An
     * unbounded value would be just as bad in the other direction: a producer
     * could replay one event with a different level each time, and every
     * value is a fresh key, so each replay would send new notifications.
     *
     * <p>A missing or out-of-range level cannot be fixed by retrying, so it is
     * raised as {@link IllegalArgumentException}, which the listener already
     * routes to the dead-letter topic and acknowledges (poison pill).
     */
    private int extractEscalationLevel(JsonNode event) {
        final JsonNode node = event.path("escalationLevel");
        if (!node.isInt()) {
            throw new IllegalArgumentException(
                    "IncidentEscalatedEvent.escalationLevel must be an integer "
                            + "between 1 and " + MAX_ESCALATION_LEVEL
                            + " (found: "
                            + (node.isMissingNode() ? "missing" : node.getNodeType())
                            + ")");
        }
        final int level = node.asInt();
        if (level < 1 || level > MAX_ESCALATION_LEVEL) {
            throw new IllegalArgumentException(
                    "IncidentEscalatedEvent.escalationLevel must be between 1 and "
                            + MAX_ESCALATION_LEVEL + " (found: " + level + ")");
        }
        return level;
    }

    /**
     * Reads the optional {@code escalateTo} user id of an
     * {@code IncidentEscalatedEvent}.
     *
     * <p>Missing, {@code null} or blank is a normal case (an escalation
     * published without a target) and yields {@code null}. A value that is
     * present but not a UUID is logged and also treated as absent rather
     * than raised as a poison pill: it is an optional hint, and dropping the
     * whole escalation notification because of a malformed hint would lose an
     * alert that can still be delivered.
     */
    private UUID extractEscalateTo(JsonNode event, UUID incidentId) {
        final JsonNode node = event.path("escalateTo");
        if (node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            // The raw value is deliberately not logged: it comes straight from
            // the Kafka payload, so logging it would let a producer forge log
            // lines (newlines) or inflate log volume.
            log.warn("Ignoring malformed escalateTo on IncidentEscalatedEvent: " +
                            "incidentId={}", incidentId);
            return null;
        }
    }

    private Severity parseSeverity(String rawSeverity, UUID incidentId) {
        try {
            return Severity.fromString(rawSeverity);
        } catch (IllegalArgumentException e) {
            throw new UnrecognizedSeverityException(rawSeverity, incidentId,
                    "notification routing");
        }
    }

    // Reads the eventType header set by IncidentEventPublisher.
    // Returns null if the header is absent or blank.
    private String extractEventType(ConsumerRecord<?, ?> record) {
        final Header header = record.headers()
                .lastHeader(IncidentEventTypes.HEADER_NAME);
        if (header != null) {
            final String value = new String(header.value(), StandardCharsets.UTF_8);
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}