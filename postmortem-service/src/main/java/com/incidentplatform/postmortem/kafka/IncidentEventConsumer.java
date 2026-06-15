package com.incidentplatform.postmortem.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.postmortem.service.PostmortemService;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@Component
public class IncidentEventConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentEventConsumer.class);

    private final PostmortemService postmortemService;
    private final ObjectMapper objectMapper;

    public IncidentEventConsumer(PostmortemService postmortemService,
                                 ObjectMapper objectMapper) {
        this.postmortemService = postmortemService;
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

            final JsonNode event = objectMapper.readTree(record.value());
            final String tenantId = extractTenantId(record, event);
            TenantContext.set(tenantId);

            if (IncidentEventTypes.INCIDENT_RESOLVED.equals(eventType)) {
                handleResolved(event, tenantId);
            } else {
                log.debug("Ignoring event type: {}", eventType);
            }

        } catch (UnrecognizedSeverityException e) {
            // Poison pill — unrecognized severity cannot be fixed by retrying.
            // Acknowledge to skip and unblock the partition.
            log.error("Skipping postmortem generation — unrecognized severity: {}",
                    e.getMessage());
            acknowledgment.acknowledge();
            return;

        } catch (IllegalArgumentException e) {
            // Poison pill — missing tenantId, bad UUID, or missing required field.
            // Retrying will never succeed.
            log.error("Poison pill in incident lifecycle event — skipping: " +
                            "topic={}, partition={}, offset={}, error={}",
                    record.topic(), record.partition(),
                    record.offset(), e.getMessage());
            acknowledgment.acknowledge();
            return;

        } catch (Exception e) {
            // Transient error (DB unavailable, Gemini API issue while saving the
            // postmortem entity). Do NOT acknowledge — Kafka will redeliver after
            // consumer restart. Postmortem generation may be delayed but will
            // not be lost.
            log.error("Transient error processing event — " +
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

        log.info("Generating postmortem for resolved incident: " +
                        "incidentId={}, tenant={}, severity={}, durationMinutes={}",
                incidentId, tenantId, severity, durationMinutes);

        postmortemService.generatePostmortem(
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

    /**
     * Resolves the tenant for a Kafka record using a three-step strategy:
     *
     * <ol>
     *   <li><b>Header</b> — reads {@code X-Tenant-Id} set by
     *       {@link TenantKafkaProducerInterceptor} (fast path, no deserialization needed).
     *   <li><b>Payload</b> — falls back to the {@code tenantId} field in the JSON body.
     *       This covers replay scenarios, manual publishes, or messages produced by a
     *       non-standard producer that skipped the interceptor.
     *   <li><b>Poison pill</b> — if absent in both, throws {@link IllegalArgumentException}
     *       so the caller's catch block routes the record to acknowledge + skip.
     * </ol>
     */
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