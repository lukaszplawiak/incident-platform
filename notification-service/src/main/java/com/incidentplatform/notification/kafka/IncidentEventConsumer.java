package com.incidentplatform.notification.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.notification.service.NotificationService;
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

        // Extract tenantId from this specific record's Kafka header.
        // The incidents-lifecycle topic is multi-tenant — each record must be
        final String tenantId = extractTenantId(record);
        TenantContext.set(tenantId);

        try {
            final JsonNode event = objectMapper.readTree(record.value());

            final String eventType = resolveEventType(record, event);
            final UUID incidentId = UUID.fromString(
                    event.get("incidentId").asText());

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

        } finally {
            TenantContext.clear();
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
        // Fallback: should not happen in production, but prevents NPE
        log.warn("Missing tenantId Kafka header, falling back to payload: " +
                        "topic={}, partition={}, offset={}",
                record.topic(), record.partition(), record.offset());
        return "unknown";
    }
}