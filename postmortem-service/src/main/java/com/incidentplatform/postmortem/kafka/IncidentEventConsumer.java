package com.incidentplatform.postmortem.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.postmortem.service.PostmortemPersistenceService;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.events.IncidentEventTypes;
import com.incidentplatform.shared.kafka.TenantKafkaProducerInterceptor;
import com.incidentplatform.shared.kafka.UnrecognizedSeverityException;
import com.incidentplatform.shared.security.TenantContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.io.IOException;
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
 */
@Component
public class IncidentEventConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentEventConsumer.class);

    private final PostmortemPersistenceService persistenceService;
    private final ObjectMapper objectMapper;

    public IncidentEventConsumer(PostmortemPersistenceService persistenceService,
                                 ObjectMapper objectMapper) {
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
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

            final JsonNode event = parseJson(record.value());
            final String tenantId = extractTenantId(record, event);
            TenantContext.set(tenantId);

            if (IncidentEventTypes.INCIDENT_RESOLVED.equals(eventType)) {
                handleResolved(event, tenantId);
            } else {
                log.debug("Ignoring event type: {}", eventType);
            }

        } catch (UnrecognizedSeverityException e) {
            // Poison pill — unrecognized severity cannot be fixed by retrying.
            log.error("Skipping postmortem generation — unrecognized severity: {}",
                    e.getMessage());
            acknowledgment.acknowledge();
            return;

        } catch (IllegalArgumentException e) {
            // Poison pill — unparseable JSON, missing tenantId, bad UUID, or
            // missing required field. Retrying will never succeed.
            log.error("Poison pill in incident lifecycle event — skipping: " +
                            "topic={}, partition={}, offset={}, error={}",
                    record.topic(), record.partition(),
                    record.offset(), e.getMessage());
            acknowledgment.acknowledge();
            return;

        } catch (Exception e) {
            // Transient error — most likely the DB INSERT for the outbox entry
            // failed (DB unavailable). Do NOT acknowledge — Kafka will redeliver
            // after consumer restart. The outbox entry was not written so there
            // is no risk of duplicate processing.
            log.error("Transient error writing outbox entry — " +
                            "will be redelivered: topic={}, partition={}, " +
                            "offset={}, error={}",
                    record.topic(), record.partition(),
                    record.offset(), e.getMessage(), e);
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

    private JsonNode parseJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Unparseable JSON payload: " + e.getMessage(), e);
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

    private String extractTenantId(ConsumerRecord<?, ?> record, JsonNode payload) {
        // Step 1 — Kafka header (set by TenantKafkaProducerInterceptor)
        final Header header = record.headers()
                .lastHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER);
        if (header != null) {
            final String tenantId = new String(header.value(), StandardCharsets.UTF_8);
            if (!tenantId.isBlank()) {
                return tenantId;
            }
        }

        // Step 2 — payload field (fallback for replay / non-interceptor producers)
        final String payloadTenantId = payload.path("tenantId").asText(null);
        if (payloadTenantId != null && !payloadTenantId.isBlank()) {
            log.warn("X-Tenant-Id header missing — resolved tenantId from payload: " +
                            "topic={}, partition={}, offset={}, tenantId={}",
                    record.topic(), record.partition(), record.offset(), payloadTenantId);
            return payloadTenantId;
        }

        // Step 3 — poison pill: tenantId absent in both header and payload
        throw new IllegalArgumentException(
                "Missing tenantId in both X-Tenant-Id header and payload.tenantId: " +
                        "topic=" + record.topic() +
                        ", partition=" + record.partition() +
                        ", offset=" + record.offset());
    }
}