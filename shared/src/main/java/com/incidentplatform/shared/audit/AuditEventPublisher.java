package com.incidentplatform.shared.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.incidentplatform.shared.dto.AuditEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Publishes audit events to Kafka for compliance and observability.
 *
 * <p>Delegates the actual Kafka send to {@link AuditEventKafkaSender} which
 * carries the {@code @Retryable} annotation. This separation is necessary
 * because Spring AOP proxies only intercept cross-bean method calls —
 * a {@code @Retryable} annotation on a {@code private} method called via
 * {@code this} is silently ignored by the proxy.
 */
@Component
public class AuditEventPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(AuditEventPublisher.class);

    private final AuditEventKafkaSender sender;

    public AuditEventPublisher(AuditEventKafkaSender sender) {
        this.sender = sender;
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

    private void publish(AuditEventMessage message) {
        try {
            sender.send(message);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize audit event — not retrying: " +
                            "eventType={}, incidentId={}",
                    message.eventType(), message.incidentId(), e);
        } catch (Exception e) {
            log.error("Failed to publish audit event after all retries: " +
                            "eventType={}, incidentId={}",
                    message.eventType(), message.incidentId(), e);
        }
    }
}