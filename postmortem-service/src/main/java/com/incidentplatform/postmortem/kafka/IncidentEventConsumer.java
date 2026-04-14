package com.incidentplatform.postmortem.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.postmortem.service.PostmortemService;
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

        try {
            final JsonNode event = objectMapper.readTree(record.value());
            final String eventType = resolveEventType(event);

            if ("IncidentResolvedEvent".equals(eventType)) {
                handleResolved(event);
            } else {
                log.debug("Ignoring event type: {}", eventType);
            }

            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process event: topic={}, partition={}, " +
                            "offset={}, error={}",
                    record.topic(), record.partition(),
                    record.offset(), e.getMessage(), e);
            acknowledgment.acknowledge();
        }
    }

    private void handleResolved(JsonNode event) {
        final UUID incidentId = UUID.fromString(
                event.get("incidentId").asText());
        final String tenantId = resolveTenantId(event);
        final String severity = event.path("severity").asText("UNKNOWN");
        final String title = event.path("title").asText("Unknown incident");
        final int durationMinutes = event.path("durationMinutes").asInt(0);

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

    private String resolveTenantId(JsonNode event) {
        final String fromContext = TenantContext.getOrNull();
        if (fromContext != null && !fromContext.isBlank()) {
            return fromContext;
        }
        return event.path("tenantId").asText("unknown");
    }

    private String resolveEventType(JsonNode event) {
        if (event.has("resolvedBy") || event.has("durationMinutes")) {
            return "IncidentResolvedEvent";
        }
        if (event.has("acknowledgedBy")) {
            return "IncidentAcknowledgedEvent";
        }
        if (event.has("escalationLevel")) {
            return "IncidentEscalatedEvent";
        }
        if (event.has("closedBy") || event.has("postmortemId")) {
            return "IncidentClosedEvent";
        }
        if (event.has("severity") && event.has("title")) {
            return "IncidentOpenedEvent";
        }
        return "UNKNOWN";
    }
}