package com.incidentplatform.notification.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.notification.service.NotificationService;
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

        final String tenantId = extractTenantId(record);
        TenantContext.set(tenantId);

        try {
            // Read eventType from Kafka header set by IncidentEventPublisher.
            // Header-based routing is explicit and stable — producers declare
            // the type, consumers don't need to guess from payload structure.
            final String eventType = extractEventType(record);
            if (eventType == null) {
                log.error("Missing {} header — skipping: topic={}, partition={}, offset={}",
                        IncidentEventTypes.HEADER_NAME,
                        record.topic(), record.partition(), record.offset());
                acknowledgment.acknowledge();
                return;
            }

            final JsonNode event = objectMapper.readTree(record.value());
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

        } catch (UnrecognizedSeverityException e) {
            // Poison pill — unrecognized severity cannot be fixed by retrying.
            // Acknowledge to skip and unblock the partition.
            log.error("Skipping notification event — unrecognized severity: {}",
                    e.getMessage());
            acknowledgment.acknowledge();
            return;

        } catch (IllegalArgumentException e) {
            // Poison pill — malformed payload (bad UUID, missing required field).
            // Our own IncidentEventPublisher produces these events so this should
            // not happen in production, but if it does retrying won't help.
            log.error("Poison pill in incident lifecycle event — skipping: " +
                            "topic={}, partition={}, offset={}, error={}",
                    record.topic(), record.partition(),
                    record.offset(), e.getMessage());
            acknowledgment.acknowledge();
            return;

        } catch (Exception e) {
            // Transient error (Slack API down, DB unavailable, network issue).
            // Do NOT acknowledge — Kafka will redeliver after consumer restart.
            // Notification may be delayed but will not be lost.
            log.error("Transient error processing incident event — " +
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

    private Severity parseSeverity(String rawSeverity, UUID incidentId) {
        try {
            return Severity.fromString(rawSeverity);
        } catch (IllegalArgumentException e) {
            throw new UnrecognizedSeverityException(rawSeverity, incidentId,
                    "notification routing");
        }
    }

    // Reads the eventType header set by IncidentEventPublisher.
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

    // Reads tenantId from the Kafka record header set by TenantKafkaProducerInterceptor.
    // Each record on a multi-tenant topic carries its own tenantId header.
    private String extractTenantId(ConsumerRecord<?, ?> record) {
        final Header header = record.headers()
                .lastHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER);
        if (header != null) {
            final String tenantId = new String(header.value(), StandardCharsets.UTF_8);
            if (!tenantId.isBlank()) {
                return tenantId;
            }
        }
        log.warn("Missing tenantId Kafka header, falling back to 'unknown': " +
                        "topic={}, partition={}, offset={}",
                record.topic(), record.partition(), record.offset());
        return "unknown";
    }
}