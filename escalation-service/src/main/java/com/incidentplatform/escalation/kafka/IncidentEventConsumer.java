package com.incidentplatform.escalation.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.incidentplatform.escalation.service.EscalationService;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.events.IncidentEventTypes;
import com.incidentplatform.shared.audit.AuditText;
import com.incidentplatform.shared.kafka.DeadLetterPublisher;
import com.incidentplatform.shared.kafka.KafkaFailures;
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
 *
 * <h2>Changed (backlog #0-96): a database outage is transient too, and
 * "transient" now means nack</h2>
 * The allow-list above missed the commonest transient failure: an
 * unreachable database is {@code DataAccessResourceFailureException} (a
 * <em>non</em>-transient {@code DataAccessException}) or, at a transaction's
 * start, {@code CannotCreateTransactionException} (no
 * {@code DataAccessException} at all), so a database outage dead-lettered
 * every event. The rule now lives in {@code shared}
 * ({@code KafkaFailures}, used through
 * {@code DeadLetterPublisher.redeliverIfTransientElseDeadLetter}) for every
 * consumer. And "do not acknowledge, Kafka redelivers" did not hold in
 * {@code MANUAL_IMMEDIATE} mode — the next record's acknowledgement
 * committed the offset past the unacknowledged one — so a transient failure
 * is {@code nack}ed, and a poison pill is acknowledged only once Kafka has
 * its dead-letter copy.
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

        // Backlog #0-91: no placeholder tenant before the record's own ("unknown"
        // used to be set, a valid tenant id that reached the dead-letter key);
        // TenantContext.clear() in finally is safe either way.

        try {
            // Read eventType from the X-Event-Type header set by
            // IncidentEventKafkaSender (used by both incident-service and
            // escalation-service producers). Header-based routing is explicit
            // and stable — no guessing from payload field presence.
            final String eventType = extractEventType(record);
            if (eventType == null) {
                // Changed (backlog #0-96): this record was acknowledged and
                // dropped with only a log line; it is now kept like any other
                // record this consumer cannot read, under its tenant when the
                // record has a trustworthy one (not counted as a tenant
                // refusal: it is refused for its header).
                log.error("Missing {} header — routing to DLT: topic={}, partition={}, offset={}",
                        IncidentEventTypes.HEADER_NAME,
                        record.topic(), record.partition(), record.offset());
                deadLetterPublisher.deadLetterThenAcknowledge(record,
                        tenantRecordResolver.trustedTenantOrNull(record),
                        "missing " + IncidentEventTypes.HEADER_NAME + " header", acknowledgment);
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
                // The header is the producer's: one line, bounded (backlog #0-96).
                default -> log.debug("Ignoring event type: {}", AuditText.error(eventType));
            }

        } catch (UnrecognizedSeverityException e) {
            // Poison pill — unrecognized severity cannot be fixed by retrying.
            // Route to DLT, then acknowledge to unblock the partition.
            final String tenantId = TenantContext.getOrNull();
            log.error("Poison pill (unrecognized severity) — routing to DLT: " +
                            "topic={}, partition={}, offset={}, tenant={}, error={}",
                    record.topic(), record.partition(), record.offset(),
                    tenantId, KafkaFailures.reason(e));

            deadLetterPublisher.deadLetterThenAcknowledge(record, tenantId,
                    KafkaFailures.reason(e), acknowledgment);
            return;

        } catch (IllegalArgumentException e) {
            // Poison pill — unparseable JSON, missing tenantId, bad UUID, or
            // missing required field. Retrying will never succeed. Route to
            // DLT, then acknowledge to unblock the partition.
            final String tenantId = TenantContext.getOrNull();
            log.error("Poison pill detected — routing to DLT: " +
                            "topic={}, partition={}, offset={}, tenant={}, error={}",
                    record.topic(), record.partition(), record.offset(),
                    tenantId, KafkaFailures.reason(e));

            deadLetterPublisher.deadLetterThenAcknowledge(record, tenantId,
                    KafkaFailures.reason(e), acknowledgment);
            return;

        } catch (Exception e) {
            // Changed (backlog #0-96): one rule for every consumer
            // (KafkaFailures). A transient failure — the database
            // unreachable, timed out, or a lost version race — is nacked and
            // read again; it used to be left unacknowledged, which the next
            // record's acknowledgement skipped. Anything else fails the same
            // way every time and is dead-lettered (backlog #47's direction).
            deadLetterPublisher.redeliverIfTransientElseDeadLetter(
                    record, TenantContext.getOrNull(), e, acknowledgment);
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