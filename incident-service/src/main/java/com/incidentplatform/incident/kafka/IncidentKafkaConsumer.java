package com.incidentplatform.incident.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.incident.service.IncidentCommandService;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.ResolvedAlertNotification;
import com.incidentplatform.shared.security.TenantContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class IncidentKafkaConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentKafkaConsumer.class);

    private final IncidentCommandService commandService;
    private final ObjectMapper objectMapper;

    public IncidentKafkaConsumer(IncidentCommandService commandService,
                                 ObjectMapper objectMapper) {
        this.commandService = commandService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${kafka.topics.alerts-raw}",
            groupId = "incident-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeAlert(ConsumerRecord<String, String> record,
                             Acknowledgment acknowledgment) {
        log.debug("Received alert: topic={}, partition={}, offset={}",
                record.topic(), record.partition(), record.offset());

        final UnifiedAlertDto alert = deserialize(record.value(),
                UnifiedAlertDto.class);

        final String tenantId = TenantContext.get();

        log.info("Processing firing alert: alertId={}, fingerprint={}, " +
                        "severity={}, source={}, tenant={}",
                alert.alertId(), alert.fingerprint(),
                alert.severity(), alert.source(), tenantId);

        commandService.createFromAlert(alert, tenantId);

        acknowledgment.acknowledge();

        log.info("Alert processed successfully: alertId={}, tenant={}",
                alert.alertId(), tenantId);
    }

    @KafkaListener(
            topics = "${kafka.topics.alerts-resolved}",
            groupId = "incident-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeResolvedAlert(ConsumerRecord<String, String> record,
                                     Acknowledgment acknowledgment) {
        log.debug("Received resolved alert: topic={}, partition={}, offset={}",
                record.topic(), record.partition(), record.offset());

        final ResolvedAlertNotification notification = deserialize(
                record.value(), ResolvedAlertNotification.class);

        final String tenantId = TenantContext.get();

        log.info("Processing resolved alert: fingerprint={}, source={}, tenant={}",
                notification.alertFingerprint(), notification.source(), tenantId);

        commandService.autoResolve(notification, tenantId);

        acknowledgment.acknowledge();

        log.info("Resolved alert processed: fingerprint={}, tenant={}",
                notification.alertFingerprint(), tenantId);
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    String.format("Failed to deserialize Kafka message to %s: %s",
                            type.getSimpleName(), e.getMessage()), e);
        }
    }
}