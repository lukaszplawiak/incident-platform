package com.incidentplatform.ingestion.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.ResolvedAlertNotification;
import com.incidentplatform.shared.kafka.TenantKafkaProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class AlertKafkaProducer {

    private static final Logger log =
            LoggerFactory.getLogger(AlertKafkaProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String alertsRawTopic;
    private final String alertsResolvedTopic;

    public AlertKafkaProducer(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${kafka.topics.alerts-raw}") String alertsRawTopic,
            @Value("${kafka.topics.alerts-resolved}") String alertsResolvedTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.alertsRawTopic = alertsRawTopic;
        this.alertsResolvedTopic = alertsResolvedTopic;
    }

    // Fire-and-forget: Kafka errors are logged but not propagated to the caller.
    // Blocking on broker availability would cause HTTP 5xx during Kafka outages —
    // Alertmanager would retry aggressively, creating alert floods.
    // Trade-off: occasional alert loss vs. endpoint availability under pressure.
    // A DLQ would eliminate message loss in a production deployment.
    public void publishFiring(UnifiedAlertDto alert) {
        try {
            final String payload = objectMapper.writeValueAsString(alert);

            // TenantKafkaProducerInterceptor still runs as a safety net but is not
            // the primary source of the X-Tenant-Id header here.
            //
            // TODO: Migrate to Envelope Pattern for a fully explicit messaging contract:
            //  Instead of setting tenantId only as a Kafka header, wrap every message in
            //  a typed KafkaEnvelope<T> carrying routing metadata (tenantId, eventType,
            //  correlationId, producedAt) alongside the domain payload. Benefits: tenantId
            //  is part of the schema (not just a header), end-to-end correlation ID
            //  tracing, easy replay with full context, consumers access metadata without
            //  deserializing the inner payload. Requires: new KafkaEnvelope<T> record in
            //  shared/, schema changes in all producers/consumers, and a consistent
            //  deserialization strategy. Justified when adding OpenTelemetry distributed
            //  tracing or when the number of producers/consumers grows significantly.
            final ProducerRecord<String, String> record = new ProducerRecord<>(
                    alertsRawTopic,
                    null,
                    alert.tenantId(),
                    payload
            );
            record.headers().add(new RecordHeader(
                    TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                    alert.tenantId().getBytes(StandardCharsets.UTF_8)
            ));

            kafkaTemplate.send(record)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish firing alert to Kafka: " +
                                            "topic={}, alertId={}, source={}, tenant={}",
                                    alertsRawTopic, alert.alertId(),
                                    alert.source(), alert.tenantId(), ex);
                        } else {
                            log.debug("Firing alert published: topic={}, " +
                                            "partition={}, offset={}, alertId={}, " +
                                            "source={}, tenant={}",
                                    alertsRawTopic,
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset(),
                                    alert.alertId(), alert.source(),
                                    alert.tenantId());
                        }
                    });

        } catch (JsonProcessingException e) {
            throw new AlertPublishException(
                    String.format("Failed to serialize firing alert: alertId=%s",
                            alert.alertId()), e);
        }
    }

    // Same fire-and-forget design as publishFiring — see above for rationale.
    public void publishResolved(ResolvedAlertNotification notification) {
        try {
            final String payload = objectMapper.writeValueAsString(notification);

            final ProducerRecord<String, String> record = new ProducerRecord<>(
                    alertsResolvedTopic,
                    null,
                    notification.tenantId(),
                    payload
            );
            record.headers().add(new RecordHeader(
                    TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                    notification.tenantId().getBytes(StandardCharsets.UTF_8)
            ));

            kafkaTemplate.send(record)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish resolved notification: " +
                                            "topic={}, eventId={}, tenant={}",
                                    alertsResolvedTopic, notification.eventId(),
                                    notification.tenantId(), ex);
                        } else {
                            log.debug("Resolved notification published: topic={}, " +
                                            "partition={}, offset={}, eventId={}, tenant={}",
                                    alertsResolvedTopic,
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset(),
                                    notification.eventId(), notification.tenantId());
                        }
                    });

        } catch (JsonProcessingException e) {
            throw new AlertPublishException(
                    String.format("Failed to serialize resolved notification: " +
                            "eventId=%s", notification.eventId()), e);
        }
    }

    public static class AlertPublishException extends RuntimeException {
        public AlertPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}