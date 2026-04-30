package com.incidentplatform.notification.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.notification.service.NotificationService;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.kafka.UnrecognizedSeverityException;
import com.incidentplatform.shared.security.TenantContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class IncidentEventConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentEventConsumer.class);

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public IncidentEventConsumer(NotificationService notificationService,
                                 ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
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

        try {
            final JsonNode event = objectMapper.readTree(record.value());

            final String eventType = resolveEventType(record, event);
            final UUID incidentId = UUID.fromString(
                    event.get("incidentId").asText());
            final String tenantId = TenantContext.getOrNull() != null
                    ? TenantContext.get()
                    : event.path("tenantId").asText("unknown");

            final Severity severity = parseSeverity(
                    event.path("severity").asText(), incidentId);

            final String title = event.path("title").asText("Unknown incident");

            log.info("Processing incident event: type={}, incidentId={}, " +
                            "severity={}, tenant={}",
                    eventType, incidentId, severity, tenantId);

            notificationService.processEvent(
                    eventType, incidentId, tenantId, severity, title);

            acknowledgment.acknowledge();

        } catch (UnrecognizedSeverityException e) {
            log.error("Skipping notification event — {}", e.getMessage());
            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process incident event: topic={}, " +
                            "partition={}, offset={}, error={}",
                    record.topic(), record.partition(),
                    record.offset(), e.getMessage(), e);
            acknowledgment.acknowledge();
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

    private String resolveEventType(ConsumerRecord<String, String> record,
                                    JsonNode event) {
        if (event.has("acknowledgedBy")) return "IncidentAcknowledgedEvent";
        if (event.has("resolvedBy") && event.has("durationMinutes")) return "IncidentResolvedEvent";
        if (event.has("closedBy") || event.has("postmortemId")) return "IncidentClosedEvent";
        if (event.has("escalationLevel")) return "IncidentEscalatedEvent";
        if (event.has("severity") && event.has("title")) return "IncidentOpenedEvent";

        log.warn("Cannot determine event type for record: key={}, " +
                "falling back to UNKNOWN", record.key());
        return "UNKNOWN";
    }
}