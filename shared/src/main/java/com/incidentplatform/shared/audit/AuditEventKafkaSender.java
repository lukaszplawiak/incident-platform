package com.incidentplatform.shared.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.shared.dto.AuditEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import com.incidentplatform.shared.kafka.TenantKafkaProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
 *
 * <h2>Fixed (backlog #0-88): the X-Tenant-Id header is set here</h2>
 * Every record is meant to carry {@code X-Tenant-Id} (CLAUDE.md, Kafka), and
 * {@code TenantKafkaProducerInterceptor} adds it only from {@code TenantContext}
 * and only in services that register it. auth-service, notification-service,
 * postmortem-service and oncall-service do not, and audit events raised outside
 * a request (a login, a scheduled job, the break-glass command) have no
 * context; found in review, 151 of 155 local audit records had no header. The
 * audit message names its tenant, so the header is set from it, as
 * {@code AlertKafkaProducer} and {@code IncidentEventKafkaSender} do. Where the
 * interceptor also runs, it leaves a record that already has the header alone,
 * so the event's own tenant is the one consumers read (found in review: it used
 * to append the context's value, and consumers read the last header).
 * {@code AuditEventConsumer} stores the payload's tenant either way.
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
        kafkaTemplate.send(record(message, payload));
        log.debug("Audit event published: eventType={}, resourceId={}, resourceType={}, tenant={}",
                message.eventType(), message.resourceId(),
                message.resourceType(), message.tenantId());
    }

    /**
     * Sends and waits for the broker's acknowledgement (backlog #0-88), for an
     * action that must not happen unaudited: {@link #send} hands the record to
     * the producer and never reads the result, so a failed delivery is lost
     * silently (backlog #0-84). One attempt, no {@code @Retryable}: the caller
     * decides what a missing confirmation means.
     *
     * @throws AuditNotConfirmedException if serialization or delivery failed,
     *                                    or no acknowledgement came within {@code timeout}
     */
    void sendConfirmed(AuditEventMessage message, Duration timeout) {
        try {
            final String payload = objectMapper.writeValueAsString(message);
            kafkaTemplate.send(record(message, payload))
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw notConfirmed(message, e);
        } catch (JsonProcessingException | ExecutionException | TimeoutException | RuntimeException e) {
            throw notConfirmed(message, e);
        }
        log.info("Audit event confirmed by Kafka: eventType={}, resourceId={}, tenant={}",
                message.eventType(), message.resourceId(), message.tenantId());
    }

    /** Keyed and headed by the event's tenant (see the class Javadoc). */
    private ProducerRecord<String, String> record(AuditEventMessage message, String payload) {
        final ProducerRecord<String, String> record =
                new ProducerRecord<>(auditEventsTopic, message.tenantId(), payload);
        if (message.tenantId() != null) {
            record.headers().add(new RecordHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                    message.tenantId().getBytes(StandardCharsets.UTF_8)));
        }
        return record;
    }

    private static AuditNotConfirmedException notConfirmed(AuditEventMessage message, Exception cause) {
        return new AuditNotConfirmedException(
                "Audit event " + message.eventType() + " for " + message.resourceId()
                        + " was not confirmed by Kafka", cause);
    }
}