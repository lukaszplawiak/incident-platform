package com.incidentplatform.ingestion.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.ResolvedAlertNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

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

    public void publishFiring(UnifiedAlertDto alert) {
        try {
            final String payload = objectMapper.writeValueAsString(alert);
            final String partitionKey = alert.tenantId();

            kafkaTemplate.send(alertsRawTopic, partitionKey, payload)
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

    public void publishResolved(ResolvedAlertNotification notification) {
        try {
            final String payload = objectMapper.writeValueAsString(notification);
            final String partitionKey = notification.tenantId();

            kafkaTemplate.send(alertsResolvedTopic, partitionKey, payload)
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