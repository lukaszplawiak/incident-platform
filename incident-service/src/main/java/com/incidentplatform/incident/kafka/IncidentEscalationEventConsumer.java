package com.incidentplatform.incident.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.incident.domain.Incident;
import com.incidentplatform.incident.repository.IncidentRepository;
import com.incidentplatform.shared.events.IncidentEventTypes;
import com.incidentplatform.shared.kafka.TenantKafkaProducerInterceptor;
import com.incidentplatform.shared.security.TenantContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Closes the loop on incident escalation: incident-service publishes
 * {@code IncidentEscalatedEvent} (via {@link com.incidentplatform.incident.service.IncidentEventPublisher}
 * for manual REST-driven escalation, and escalation-service publishes the same
 * event type for automatic timeout-driven escalation) — but until this consumer
 * existed, incident-service never read its own event back, so
 * {@code Incident.escalationLevel} only reflected manual changes and was never
 * updated by escalation-service's automatic {@code EscalationScheduler}.
 *
 * <p>This consumer listens to {@code incidents.lifecycle} for
 * {@code IncidentEscalatedEvent} specifically and calls
 * {@link Incident#recordEscalation(int)} — a pure attribute update that does
 * NOT go through {@link com.incidentplatform.incident.domain.IncidentFsm},
 * since escalation level is independent of the main lifecycle status.
 *
 * <p>Uses the same header → payload → poison-pill tenant/event-type extraction
 * pattern as the other {@code incidents.lifecycle} consumers
 * (escalation-service, notification-service, postmortem-service) for
 * consistency across the platform.
 */
@Component
public class IncidentEscalationEventConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentEscalationEventConsumer.class);

    private final IncidentRepository incidentRepository;
    private final ObjectMapper objectMapper;

    public IncidentEscalationEventConsumer(IncidentRepository incidentRepository,
                                           ObjectMapper objectMapper) {
        this.incidentRepository = incidentRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${kafka.topics.incidents-lifecycle}",
            groupId = "incident-service-escalation-sync",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void consumeIncidentEvent(ConsumerRecord<String, String> record,
                                     Acknowledgment acknowledgment) {
        log.debug("Received incident event: topic={}, partition={}, offset={}",
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

            if (!IncidentEventTypes.INCIDENT_ESCALATED.equals(eventType)) {
                // This consumer only cares about escalation — all other event
                // types on this topic are handled by IncidentKafkaConsumer's
                // own write path (this service is the producer of those, not
                // a self-consumer for them).
                acknowledgment.acknowledge();
                return;
            }

            final JsonNode event = parseJson(record.value());
            final String tenantId = extractTenantId(record, event);
            TenantContext.set(tenantId);

            handleEscalated(event, tenantId);

        } catch (IllegalArgumentException e) {
            // Poison pill — unparseable JSON, missing tenantId, bad UUID, or
            // missing required field. Retrying will never succeed.
            log.error("Poison pill in incident escalation event — skipping: " +
                            "topic={}, partition={}, offset={}, error={}",
                    record.topic(), record.partition(),
                    record.offset(), e.getMessage());
            acknowledgment.acknowledge();
            return;

        } catch (Exception e) {
            // Transient error (DB unavailable). Do NOT acknowledge — Kafka
            // will redeliver after consumer restart.
            log.error("Transient error processing escalation event — " +
                            "will be redelivered: topic={}, partition={}, " +
                            "offset={}, error={}",
                    record.topic(), record.partition(),
                    record.offset(), e.getMessage(), e);
            return;

        } finally {
            TenantContext.clear();
        }

        acknowledgment.acknowledge();
    }

    private void handleEscalated(JsonNode event, String tenantId) {
        final UUID incidentId = UUID.fromString(
                event.get("incidentId").asText());
        final int escalationLevel = event.path("escalationLevel").asInt(0);

        incidentRepository.findByIdAndTenantId(incidentId, tenantId)
                .ifPresentOrElse(
                        incident -> {
                            incident.recordEscalation(escalationLevel);
                            incidentRepository.save(incident);

                            log.info("Escalation level recorded: incidentId={}, " +
                                            "level={}, tenant={}",
                                    incidentId, escalationLevel, tenantId);
                        },
                        () -> log.warn("Escalated incident not found locally — " +
                                        "skipping: incidentId={}, tenant={}",
                                incidentId, tenantId)
                );
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
        final Header header = record.headers()
                .lastHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER);
        if (header != null) {
            final String tenantId = new String(header.value(), StandardCharsets.UTF_8);
            if (!tenantId.isBlank()) {
                return tenantId;
            }
        }

        final String payloadTenantId = payload.path("tenantId").asText(null);
        if (payloadTenantId != null && !payloadTenantId.isBlank()) {
            log.warn("X-Tenant-Id header missing — resolved tenantId from payload: " +
                            "topic={}, partition={}, offset={}, tenantId={}",
                    record.topic(), record.partition(), record.offset(), payloadTenantId);
            return payloadTenantId;
        }

        throw new IllegalArgumentException(
                "Missing tenantId in both X-Tenant-Id header and payload.tenantId: " +
                        "topic=" + record.topic() +
                        ", partition=" + record.partition() +
                        ", offset=" + record.offset());
    }
}