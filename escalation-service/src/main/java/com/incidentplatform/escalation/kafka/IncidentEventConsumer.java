package com.incidentplatform.escalation.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.escalation.service.EscalationService;
import com.incidentplatform.shared.domain.Severity;
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

    private final EscalationService escalationService;
    private final ObjectMapper objectMapper;

    public IncidentEventConsumer(EscalationService escalationService,
                                 ObjectMapper objectMapper) {
        this.escalationService = escalationService;
        this.objectMapper = objectMapper;
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

        // Extract tenantId from this specific record's Kafka header.
        // The incidents-lifecycle topic is multi-tenant — each record must be
        final String tenantId = extractTenantId(record);
        TenantContext.set(tenantId);

        try {
            final JsonNode event = objectMapper.readTree(record.value());
            final String eventType = resolveEventType(event);

            switch (eventType) {
                case "IncidentOpenedEvent" -> handleOpened(record, event, tenantId);
                case "IncidentAcknowledgedEvent" -> handleAcknowledged(event, tenantId);
                default -> log.debug("Ignoring event type: {}", eventType);
            }

            acknowledgment.acknowledge();

        } catch (UnrecognizedSeverityException e) {
            log.error("Skipping escalation event — {}", e.getMessage());
            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process incident event: topic={}, " +
                            "partition={}, offset={}, error={}",
                    record.topic(), record.partition(),
                    record.offset(), e.getMessage(), e);
            acknowledgment.acknowledge();

        } finally {
            TenantContext.clear();
        }
    }

    private void handleOpened(ConsumerRecord<?, ?> record,
                              JsonNode event,
                              String tenantId) {
        final UUID incidentId = UUID.fromString(
                event.get("incidentId").asText());
        final String title = event.path("title").asText("Unknown incident");

        final Severity severity = parseSeverity(
                event.path("severity").asText(), incidentId);

        final Instant openedAt = event.has("occurredAt")
                ? Instant.parse(event.get("occurredAt").asText())
                : Instant.now();

        log.info("Scheduling escalation for opened incident: " +
                        "incidentId={}, tenant={}, severity={}",
                incidentId, tenantId, severity);

        escalationService.scheduleEscalation(
                incidentId, tenantId, openedAt, severity, title);
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

    private String resolveEventType(JsonNode event) {
        if (event.has("acknowledgedBy")) return "IncidentAcknowledgedEvent";
        if (event.has("resolvedBy") && event.has("durationMinutes")) return "IncidentResolvedEvent";
        if (event.has("closedBy") || event.has("postmortemId")) return "IncidentClosedEvent";
        if (event.has("escalationLevel")) return "IncidentEscalatedEvent";
        if (event.has("severity") && event.has("title")) return "IncidentOpenedEvent";
        return "UNKNOWN";
    }

    // Reads tenantId from the Kafka record header set by TenantKafkaProducerInterceptor.
    // Each record on a multi-tenant topic carries its own tenantId header —
    private String extractTenantId(ConsumerRecord<?, ?> record) {
        final Header header = record.headers()
                .lastHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER);
        if (header != null) {
            final String tenantId = new String(header.value(), StandardCharsets.UTF_8);
            if (!tenantId.isBlank()) {
                return tenantId;
            }
        }
        log.warn("Missing tenantId Kafka header, falling back to unknown: " +
                        "topic={}, partition={}, offset={}",
                record.topic(), record.partition(), record.offset());
        return "unknown";
    }
}