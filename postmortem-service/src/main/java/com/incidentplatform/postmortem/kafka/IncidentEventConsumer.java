package com.incidentplatform.postmortem.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.incidentplatform.postmortem.service.PostmortemPersistenceService;
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
 * consumer does NOT acknowledge — Kafka redelivers the event after restart.
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

        TenantContext.set("unknown");

        try {
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

            if (IncidentEventTypes.INCIDENT_RESOLVED.equals(eventType)) {
                handleResolved(event, tenantId);
            } else {
                log.debug("Ignoring event type: {}", eventType);
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
            // Fixed (backlog #47): genuinely transient — most likely the
            // DB INSERT for the outbox entry failed (connection pool
            // exhaustion, query timeout). Spring's
            // TransientDataAccessException hierarchy is the authoritative
            // signal for "retrying this, unmodified, might succeed" — see
            // this class's own Javadoc. Do NOT acknowledge — Kafka will
            // redeliver after consumer restart. The outbox entry was not
            // written, so there is no risk of duplicate processing.
            log.error("Transient error writing outbox entry — " +
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