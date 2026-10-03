package com.incidentplatform.postmortem.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.incidentplatform.postmortem.service.PostmortemPersistenceService;
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
 * Kafka consumer for incident lifecycle events.
 *
 * <h2>Outbox Pattern — why this consumer does less than before</h2>
 * Previously this consumer called {@code PostmortemService.generatePostmortem()}
 * which synchronously invoked the Gemini AI API (typically 3–15 seconds).
 * The Kafka consumer thread was blocked for the entire duration of that HTTP
 * call, reducing throughput and risking {@code max.poll.interval.ms} breaches
 * under load.
 *
 * <p>This consumer now only writes an outbox entry — a single fast DB INSERT
 * via {@link PostmortemPersistenceService#createGeneratingRecord}. The actual
 * Gemini call happens in {@code PostmortemRetryScheduler}, which runs in a
 * separate scheduled thread and has no impact on Kafka consumer throughput.
 *
 * <h2>Acknowledgment guarantee</h2>
 * {@code acknowledge()} is called only after the outbox entry is durably
 * written to the database. If the DB write fails (transient error), the
 * consumer {@code nack}s the record and it is read again (backlog #0-96: it
 * used to be left unacknowledged, which did not bring it back).
 * Once the outbox entry is committed, the Gemini call is the scheduler's
 * responsibility: even a process crash after acknowledge() cannot lose the
 * work because the GENERATING record survives in the database and the
 * scheduler will pick it up.
 *
 * <h2>Fixed (backlog #47): retry classification was a deny-list, now an
 * allow-list</h2>
 * Previously classified errors as "known poison pill" ({@code
 * UnrecognizedSeverityException}, {@code IllegalArgumentException}) vs.
 * "assume transient, retry forever" (a generic {@code catch (Exception e)}
 * covering everything else). {@code Instant.parse(...)} in
 * {@link #handleResolved} throws {@code DateTimeParseException} on a
 * malformed timestamp — which extends {@code DateTimeException extends
 * RuntimeException}, NOT {@code IllegalArgumentException} — so it fell
 * through, uncaught, into the generic branch: a message that could never
 * succeed no matter how many times retried was treated as transient,
 * never acknowledged, and redelivered forever, permanently blocking this
 * partition (this exact same bug was found duplicated in
 * escalation-service's {@code IncidentEventConsumer}).
 *
 * <p>Inverted the model to an allow-list: only
 * {@link org.springframework.dao.TransientDataAccessException} (Spring's
 * own, authoritative "this is genuinely worth retrying" hierarchy — e.g.
 * connection pool exhaustion, query timeout) is treated as transient.
 * Everything else — including any future exception type nobody
 * explicitly anticipated, not just {@code DateTimeParseException} —
 * defaults to poison-pill handling (DLT + acknowledge) instead of
 * defaulting to "retry forever". A genuine, unexpected programming error
 * (e.g. a {@code NullPointerException}) is also correctly poison-pill
 * handled under this model: retrying a deterministic bug against the
 * same message would never succeed either, so surfacing it loudly via
 * DLT and moving on is strictly better than blocking the partition
 * forever on it.
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

    private final PostmortemPersistenceService persistenceService;
    private final DeadLetterPublisher deadLetterPublisher;
    private final TenantKafkaRecordResolver tenantRecordResolver;

    public IncidentEventConsumer(PostmortemPersistenceService persistenceService,
                                 DeadLetterPublisher deadLetterPublisher,
                                 TenantKafkaRecordResolver tenantRecordResolver) {
        this.persistenceService = persistenceService;
        this.deadLetterPublisher = deadLetterPublisher;
        this.tenantRecordResolver = tenantRecordResolver;
    }

    @KafkaListener(
            topics = "${kafka.topics.incidents-lifecycle}",
            groupId = "postmortem-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeIncidentEvent(ConsumerRecord<String, String> record,
                                     Acknowledgment acknowledgment) {
        log.debug("Received event: topic={}, partition={}, offset={}",
                record.topic(), record.partition(), record.offset());

        // Backlog #0-91: no placeholder tenant before the record's own ("unknown"
        // used to be set, a valid tenant id that reached the dead-letter key).

        try {
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

            if (IncidentEventTypes.INCIDENT_RESOLVED.equals(eventType)) {
                handleResolved(event, tenantId);
            } else {
                // The header is the producer's: one line, bounded (backlog #0-96).
                log.debug("Ignoring event type: {}", AuditText.error(eventType));
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

        // Reached only on success — outbox entry committed, safe to acknowledge.
        acknowledgment.acknowledge();
    }

    /**
     * Writes a GENERATING outbox entry to the database.
     *
     * <p>This is the only work the consumer thread does for a resolved event.
     * The Gemini call happens later in {@code PostmortemRetryScheduler} —
     * in a separate thread, with no impact on Kafka consumer throughput.
     *
     * <p>If a postmortem already exists for this incident (idempotency guard
     * in {@code PostmortemPersistenceService}), this is a no-op.
     */
    private void handleResolved(JsonNode event, String tenantId) {
        final UUID incidentId = UUID.fromString(
                event.get("incidentId").asText());
        final String title = event.path("title").asText("Unknown incident");
        final int durationMinutes = event.path("durationMinutes").asInt(0);

        final Severity severity = parseSeverity(
                event.path("severity").asText(), incidentId);

        final Instant openedAt = event.has("openedAt")
                ? Instant.parse(event.get("openedAt").asText())
                : Instant.now().minusSeconds(durationMinutes * 60L);

        final Instant resolvedAt = event.has("occurredAt")
                ? Instant.parse(event.get("occurredAt").asText())
                : Instant.now();

        log.info("Writing postmortem outbox entry for resolved incident: " +
                        "incidentId={}, tenant={}, severity={}, durationMinutes={}",
                incidentId, tenantId, severity, durationMinutes);

        persistenceService.createGeneratingRecord(
                incidentId, tenantId, title, severity,
                openedAt, resolvedAt, durationMinutes);
    }

    private Severity parseSeverity(String rawSeverity, UUID incidentId) {
        try {
            return Severity.fromString(rawSeverity);
        } catch (IllegalArgumentException e) {
            throw new UnrecognizedSeverityException(rawSeverity, incidentId,
                    "postmortem generation");
        }
    }

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