package com.incidentplatform.incident.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.incident.domain.AuditEvent;
import com.incidentplatform.incident.repository.AuditEventRepository;
import com.incidentplatform.shared.dto.AuditEventMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class AuditEventConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(AuditEventConsumer.class);

    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;

    public AuditEventConsumer(AuditEventRepository auditEventRepository,
                              ObjectMapper objectMapper) {
        this.auditEventRepository = auditEventRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${kafka.topics.audit-events}",
            groupId = "incident-service-audit",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record,
                        Acknowledgment acknowledgment) {
        log.debug("Received audit event: topic={}, partition={}, offset={}",
                record.topic(), record.partition(), record.offset());

        try {
            final AuditEventMessage message = objectMapper.readValue(
                    record.value(), AuditEventMessage.class);

            final AuditEvent auditEvent = toEntity(message);
            auditEventRepository.save(auditEvent);

            log.debug("Audit event saved: eventType={}, incidentId={}, " +
                            "tenant={}", message.eventType(), message.incidentId(),
                    message.tenantId());

        } catch (Exception e) {
            log.error("Failed to process audit event: partition={}, " +
                            "offset={}, error={}", record.partition(),
                    record.offset(), e.getMessage(), e);
        } finally {
            acknowledgment.acknowledge();
        }
    }

    private AuditEvent toEntity(AuditEventMessage message) {
        if ("USER".equals(message.actorType())) {
            return AuditEvent.user(
                    message.incidentId(),
                    message.tenantId(),
                    message.eventType(),
                    message.sourceService(),
                    message.actor(),
                    message.detail(),
                    message.metadata()
            );
        } else {
            return AuditEvent.system(
                    message.incidentId(),
                    message.tenantId(),
                    message.eventType(),
                    message.sourceService(),
                    message.detail(),
                    message.metadata()
            );
        }
    }
}