package com.incidentplatform.shared.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.shared.dto.AuditEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class AuditEventPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(AuditEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${kafka.topics.audit-events:audit.events}")
    private String auditEventsTopic;

    public AuditEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                               ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publishSystem(UUID incidentId,
                              String tenantId,
                              String eventType,
                              String sourceService,
                              String detail,
                              Map<String, Object> metadata) {
        publish(AuditEventMessage.system(
                incidentId, tenantId, eventType,
                sourceService, detail, metadata));
    }

    public void publishUser(UUID incidentId,
                            String tenantId,
                            String eventType,
                            String sourceService,
                            String userId,
                            String detail,
                            Map<String, Object> metadata) {
        publish(AuditEventMessage.user(
                incidentId, tenantId, eventType,
                sourceService, userId, detail, metadata));
    }

    @Retryable(
            retryFor = Exception.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 500, multiplier = 2)
    )
    private void publish(AuditEventMessage message) {
        try {
            final String payload = objectMapper.writeValueAsString(message);
            kafkaTemplate.send(auditEventsTopic, message.tenantId(), payload);
            log.debug("Audit event published: eventType={}, incidentId={}, " +
                            "tenant={}", message.eventType(), message.incidentId(),
                    message.tenantId());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize audit event: eventType={}, " +
                            "incidentId={}", message.eventType(),
                    message.incidentId(), e);
        } catch (Exception e) {
            log.error("Failed to publish audit event: eventType={}, " +
                            "incidentId={}", message.eventType(),
                    message.incidentId(), e);
        }
    }
}