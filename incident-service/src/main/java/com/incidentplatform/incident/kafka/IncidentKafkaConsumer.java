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
        log.debug("Received alert from Kafka: topic={}, partition={}, offset={}",
                record.topic(), record.partition(), record.offset());

        try {
            final UnifiedAlertDto alert = objectMapper.readValue(
                    record.value(), UnifiedAlertDto.class);

            final String tenantId = TenantContext.get();

            log.info("Processing firing alert: alertId={}, fingerprint={}, " +
                            "severity={}, source={}, tenant={}",
                    alert.alertId(), alert.fingerprint(),
                    alert.severity(), alert.source(), tenantId);

            commandService.createFromAlert(alert, tenantId);

            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process alert from Kafka: topic={}, partition={}, " +
                            "offset={}, key={}. Error: {}",
                    record.topic(), record.partition(), record.offset(),
                    record.key(), e.getMessage(), e);

            // ACK mimo błędu — nie blokuj partycji
            // TODO Enterprise #2: wysyłaj na DLQ zamiast ignorować
            acknowledgment.acknowledge();
        }
    }

    @KafkaListener(
            topics = "${kafka.topics.alerts-resolved}",
            groupId = "incident-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeResolvedAlert(ConsumerRecord<String, String> record,
                                     Acknowledgment acknowledgment) {
        log.debug("Received resolved alert from Kafka: topic={}, partition={}, " +
                "offset={}", record.topic(), record.partition(), record.offset());

        try {
            final ResolvedAlertNotification notification = objectMapper.readValue(
                    record.value(), ResolvedAlertNotification.class);

            final String tenantId = TenantContext.get();

            log.info("Processing resolved alert: fingerprint={}, source={}, tenant={}",
                    notification.alertFingerprint(), notification.source(), tenantId);

            commandService.autoResolve(notification, tenantId);

            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process resolved alert from Kafka: topic={}, " +
                            "partition={}, offset={}. Error: {}",
                    record.topic(), record.partition(), record.offset(),
                    e.getMessage(), e);

            acknowledgment.acknowledge();
        }
    }
}