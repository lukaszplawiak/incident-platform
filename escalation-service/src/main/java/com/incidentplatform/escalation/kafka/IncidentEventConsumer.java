package com.incidentplatform.escalation.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.escalation.service.EscalationService;
import com.incidentplatform.shared.security.TenantContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

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

        try {
            final JsonNode event = objectMapper.readTree(record.value());
            final String eventType = resolveEventType(event);

            switch (eventType) {
                case "IncidentOpenedEvent" -> handleOpened(event);
                case "IncidentAcknowledgedEvent" -> handleAcknowledged(event);
                default -> log.debug("Ignoring event type: {}", eventType);
            }

            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process incident event: topic={}, " +
                            "partition={}, offset={}, error={}",
                    record.topic(), record.partition(),
                    record.offset(), e.getMessage(), e);

            acknowledgment.acknowledge();
        }
    }

    private void handleOpened(JsonNode event) {
        final UUID incidentId = UUID.fromString(
                event.get("incidentId").asText());
        final String tenantId = resolveTenantId(event);
        final String severity = event.path("severity").asText("UNKNOWN");
        final String title = event.path("title").asText("Unknown incident");

        final Instant openedAt = event.has("occurredAt")
                ? Instant.parse(event.get("occurredAt").asText())
                : Instant.now();

        log.info("Scheduling escalation for opened incident: " +
                        "incidentId={}, tenant={}, severity={}",
                incidentId, tenantId, severity);

        escalationService.scheduleEscalation(
                incidentId, tenantId, openedAt, severity, title);
    }

    private void handleAcknowledged(JsonNode event) {
        final UUID incidentId = UUID.fromString(
                event.get("incidentId").asText());
        final String tenantId = resolveTenantId(event);

        log.info("Cancelling escalation (ACK received): " +
                "incidentId={}, tenant={}", incidentId, tenantId);

        escalationService.cancelEscalation(incidentId, tenantId);
    }

    private String resolveTenantId(JsonNode event) {
        final String fromContext = TenantContext.getOrNull();
        if (fromContext != null && !fromContext.isBlank()) {
            return fromContext;
        }
        return event.path("tenantId").asText("unknown");
    }

    private String resolveEventType(JsonNode event) {
        if (event.has("acknowledgedBy")) {
            return "IncidentAcknowledgedEvent";
        }
        if (event.has("resolvedBy") && event.has("durationMinutes")) {
            return "IncidentResolvedEvent";
        }
        if (event.has("closedBy") || event.has("postmortemId")) {
            return "IncidentClosedEvent";
        }
        if (event.has("escalationLevel")) {
            return "IncidentEscalatedEvent";
        }
        if (event.has("severity") && event.has("title")) {
            return "IncidentOpenedEvent";
        }
        return "UNKNOWN";
    }
}