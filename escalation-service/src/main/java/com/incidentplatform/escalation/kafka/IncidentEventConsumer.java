package com.incidentplatform.escalation.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.incidentplatform.escalation.service.EscalationService;
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
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Kafka consumer for incident lifecycle events — schedules escalations
 * for opened incidents, cancels them on acknowledgment.
 *
 * <h2>Fixed (backlog #47): retry classification was a deny-list, now an
 * allow-list</h2>
 * Previously classified errors as "known poison pill" ({@code
 * UnrecognizedSeverityException}, {@code IllegalArgumentException}) vs.
 * "assume transient, retry forever" (a generic {@code catch (Exception e)}
 * covering everything else). {@code Instant.parse(...)} in
 * {@link #handleOpened} throws {@code DateTimeParseException} on a
 * malformed timestamp — which extends {@code DateTimeException extends
 * RuntimeException}, NOT {@code IllegalArgumentException} — so it fell
 * through, uncaught, into the generic branch: a message that could never
 * succeed no matter how many times retried was treated as transient,
 * never acknowledged, and redelivered forever, permanently blocking this
 * partition (this exact same bug was found duplicated in
 * postmortem-service's {@code IncidentEventConsumer}, where it was first
 * caught).
 *
 * <p>Inverted the model to an allow-list: only
 * {@link org.springframework.dao.TransientDataAccessException} (Spring's
 * own, authoritative "this is genuinely worth retrying" hierarchy — e.g.
 * connection pool exhaustion, query timeout, and — importantly for this
 * consumer specifically — {@code ObjectOptimisticLockingFailureException}
 * extends ... extends {@code TransientDataAccessException}, so
 * {@code EscalationService.cancelEscalation}'s deliberate optimistic-lock
 * propagation from backlog #38 is still correctly treated as retryable
 * under this new model, unchanged) is treated as transient. Everything
 * else — including any future exception type nobody explicitly
 * anticipated — defaults to poison-pill handling (DLT + acknowledge)
 * instead of defaulting to "retry forever".
 */
@Component
public class IncidentEventConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentEventConsumer.class);

    private final EscalationService escalationService;
    private final DeadLetterPublisher deadLetterPublisher;
    private final TenantKafkaRecordResolver tenantRecordResolver;

    public IncidentEventConsumer(EscalationService escalationService,
                                 DeadLetterPublisher deadLetterPublisher,
                                 TenantKafkaRecordResolver tenantRecordResolver) {
        this.escalationService = escalationService;
        this.deadLetterPublisher = deadLetterPublisher;
        this.tenantRecordResolver = tenantRecordResolver;
    }

    @KafkaListener(
            topics = "${kafka.topics.incidents-lifecycle}",
            groupId = "escalation-service",
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
            // Read eventType from the X-Event-Type header set by
            // IncidentEventKafkaSender (used by both incident-service and
            // escalation-service producers). Header-based routing is explicit
            // and stable — no guessing from payload field presence.
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

            switch (eventType) {
                case IncidentEventTypes.INCIDENT_OPENED ->
                        handleOpened(event, tenantId);
                case IncidentEventTypes.INCIDENT_ACKNOWLEDGED ->
                        handleAcknowledged(event, tenantId);
                default -> log.debug("Ignoring event type: {}", eventType);
            }

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

        } catch (TransientDataAccessException e) {
            // Fixed (backlog #47): genuinely transient — most likely a DB
            // write failure (connection pool exhaustion, query timeout)
            // while scheduling/cancelling an escalation, OR (see this
            // class's own Javadoc) EscalationService.cancelEscalation's
            // deliberate ObjectOptimisticLockingFailureException
            // propagation from backlog #38 — both are
            // TransientDataAccessException, Spring's own authoritative
            // signal for "retrying this, unmodified, might succeed". Do
            // NOT acknowledge — Kafka will redeliver after consumer
            // restart. Escalation scheduling may be delayed but will not
            // be lost.
            log.error("Transient error processing incident event — " +
                            "will be redelivered: topic={}, partition={}, " +
                            "offset={}, error={}",
                    record.topic(), record.partition(),
                    record.offset(), e.getMessage(), e);
            return;

        } catch (Exception e) {
            // Fixed (backlog #47): this used to be the "assume transient,
            // retry forever" default — the exact branch that let
            // DateTimeParseException fall through uncaught. Now inverted:
            // only the specific TransientDataAccessException catch above
            // is treated as worth retrying. Anything else reaching this
            // generic catch — including DateTimeParseException, and any
            // future exception type nobody explicitly anticipated — is
            // routed to DLT + acknowledged, exactly like the specific
            // poison-pill catches above, rather than blocking this
            // partition forever. See this class's own Javadoc for why
            // this default direction, not "assume transient", is the
            // safer one — a genuine, unexpected programming error is
            // also correctly poison-pill handled this way, since
            // retrying a deterministic bug would never succeed either.
            final String tenantId = TenantContext.getOrNull();
            log.error("Unexpected error (not a recognized transient failure) — " +
                            "routing to DLT: topic={}, partition={}, offset={}, " +
                            "tenant={}, error={}",
                    record.topic(), record.partition(), record.offset(),
                    tenantId, e.getMessage(), e);

            deadLetterPublisher.publish(
                    record.value(),
                    record.topic(),
                    tenantId != null ? tenantId : "unknown",
                    e.getMessage());
            acknowledgment.acknowledge();
            return;

        } finally {
            TenantContext.clear();
        }

        // Reached only on success — all error paths return early above.
        acknowledgment.acknowledge();
    }

    private void handleOpened(JsonNode event, String tenantId) {
        final UUID incidentId = UUID.fromString(
                event.get("incidentId").asText());
        final String title = event.path("title").asText("Unknown incident");

        final Severity severity = parseSeverity(
                event.path("severity").asText(), incidentId);

        final Instant openedAt = event.has("occurredAt")
                ? Instant.parse(event.get("occurredAt").asText())
                : Instant.now();

        // Extract teamId from IncidentOpenedEvent — set by incident-service
        // from UnifiedAlertDto.teamId (resolved via Integration ApiKey).
        // Null for manually-created incidents or pre-routing incidents.
        final UUID teamId = event.has("teamId") && !event.get("teamId").isNull()
                ? UUID.fromString(event.get("teamId").asText())
                : null;

        log.info("Scheduling escalation for opened incident: " +
                        "incidentId={}, tenant={}, severity={}, teamId={}",
                incidentId, tenantId, severity, teamId);

        escalationService.scheduleEscalation(
                incidentId, tenantId, teamId, openedAt, severity, title);
    }

    private void handleAcknowledged(JsonNode event, String tenantId) {
        final UUID incidentId = UUID.fromString(
                event.get("incidentId").asText());

        log.info("Cancelling escalation (ACK received): " +
                "incidentId={}, tenant={}", incidentId, tenantId);

        escalationService.cancelEscalation(incidentId, tenantId);
    }

    private Severity parseSeverity(String rawSeverity, UUID incidentId) {
        try {
            return Severity.fromString(rawSeverity);
        } catch (IllegalArgumentException e) {
            throw new UnrecognizedSeverityException(rawSeverity, incidentId,
                    "escalation scheduling");
        }
    }

    // Reads the eventType header set by IncidentEventKafkaSender.
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