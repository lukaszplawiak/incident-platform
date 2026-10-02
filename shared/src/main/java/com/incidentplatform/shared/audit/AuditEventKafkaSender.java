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
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * The Kafka side of audit events: {@link #sendForRelay} for the outbox relay
 * (backlog #0-84), {@link #send} for services that do not use the outbox yet.
 *
 * <h2>Changed (backlog #0-84)</h2>
 * {@code send} used to carry {@code @Retryable} (3 attempts) and was described
 * as retrying; no module enables Spring Retry ({@code @EnableRetry}), so the
 * annotation did nothing and was removed. Retrying is now the outbox relay's
 * job ({@link AuditOutboxRelay}), which waits for every acknowledgement. The
 * confirmed send for the break-glass reset ({@code sendConfirmed}, #0-88) is
 * gone with it: that reset writes to the outbox like every other action.
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

    /**
     * Hands the event to the producer without waiting (services without an
     * outbox, until they move to one: backlog #0-84). A delivery that fails
     * later is lost; the outbox is the fix.
     */
    void send(AuditEventMessage message) throws JsonProcessingException {
        final String payload = objectMapper.writeValueAsString(message);
        kafkaTemplate.send(record(message.tenantId(), payload));
        log.debug("Audit event published: eventType={}, resourceId={}, resourceType={}, tenant={}",
                message.eventType(), message.resourceId(),
                message.resourceType(), message.tenantId());
    }

    /** The JSON the relay stores and later sends (backlog #0-84). */
    String serialize(AuditEventMessage message) throws JsonProcessingException {
        return objectMapper.writeValueAsString(message);
    }

    /**
     * Hands an event the outbox stored to the producer (backlog #0-84) and
     * returns Kafka's acknowledgement to wait for: the relay sends a whole
     * batch first and then waits, one round trip per batch rather than per
     * event (found in review). On the relay's thread only, never on a request.
     * The call itself blocks at most the producer's {@code max.block.ms} (set
     * in the services with an outbox) while Kafka's metadata is missing.
     */
    CompletableFuture<?> sendForRelay(String tenantId, String payload) {
        return kafkaTemplate.send(record(tenantId, payload));
    }

    /** Keyed and headed by the event's tenant (see the class Javadoc). */
    private ProducerRecord<String, String> record(String tenantId, String payload) {
        final ProducerRecord<String, String> record =
                new ProducerRecord<>(auditEventsTopic, tenantId, payload);
        if (tenantId != null) {
            record.headers().add(new RecordHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                    tenantId.getBytes(StandardCharsets.UTF_8)));
        }
        return record;
    }
}
