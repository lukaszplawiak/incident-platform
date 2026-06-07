package com.incidentplatform.shared.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.shared.dto.AuditEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * Handles the actual Kafka send for audit events with retry support.
 *
 * <p>Extracted from {@link AuditEventPublisher} as a separate Spring bean so that
 * {@code @Retryable} works correctly via AOP proxy. Spring AOP intercepts calls
 * only when they go through the proxy — i.e. when one bean calls another bean's
 * method. A {@code private} method called via {@code this} inside the same class
 * is never intercepted by the proxy, making {@code @Retryable} a no-op there.
 *
 * <p>By placing the retryable send logic here, {@link AuditEventPublisher} calls
 * this bean through the proxy, and all three retry attempts fire correctly when
 * the Kafka broker is temporarily unavailable.
 */
@Component
class AuditEventKafkaSender {

    private static final Logger log =
            LoggerFactory.getLogger(AuditEventKafkaSender.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String auditEventsTopic;

    AuditEventKafkaSender(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${kafka.topics.audit-events:audit.events}") String auditEventsTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.auditEventsTopic = auditEventsTopic;
    }

    // @Retryable works here because AuditEventPublisher calls this method
    // through the Spring proxy (cross-bean call), not via this.method().
    // Retries: 3 attempts, 500ms → 1000ms → 2000ms backoff.
    // JsonProcessingException is excluded — serialization failures are not
    // transient and retrying them would always fail.
    @Retryable(
            retryFor = Exception.class,
            noRetryFor = JsonProcessingException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 500, multiplier = 2)
    )
    void send(AuditEventMessage message) throws JsonProcessingException {
        final String payload = objectMapper.writeValueAsString(message);
        kafkaTemplate.send(auditEventsTopic, message.tenantId(), payload);
        log.debug("Audit event published: eventType={}, incidentId={}, tenant={}",
                message.eventType(), message.incidentId(), message.tenantId());
    }
}