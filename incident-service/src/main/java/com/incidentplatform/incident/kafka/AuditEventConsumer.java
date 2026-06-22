package com.incidentplatform.incident.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.incident.domain.AuditEvent;
import com.incidentplatform.incident.repository.AuditEventRepository;
import com.incidentplatform.shared.audit.ActorType;
import com.incidentplatform.shared.dto.AuditEventMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.io.IOException;

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

        } catch (IOException | IllegalArgumentException e) {
            // Poison pill — unparseable JSON or structurally invalid payload.
            // Retrying will never succeed. Acknowledge to skip and unblock the
            // partition. A gap in the audit trail is preferable to an infinite
            // retry loop blocking all subsequent audit events.
            //
            // Note: audit trail completeness is best-effort by design — the
            // audit.events topic carries observability data, not business-critical
            // state. Gaps caused by poison pills are acceptable and should be
            // investigated via the logged error below.
            log.error("Poison pill in audit event — skipping: " +
                            "topic={}, partition={}, offset={}, error={}",
                    record.topic(), record.partition(),
                    record.offset(), e.getMessage());
            acknowledgment.acknowledge();
            return;

        } catch (Exception e) {
            // Transient error (DB unavailable, connection pool exhausted).
            // Do NOT acknowledge — Kafka will redeliver after consumer restart.
            // At-least-once delivery for audit events is preferred over losing
            // entries permanently when the DB is temporarily unavailable.
            log.error("Transient error persisting audit event — " +
                            "will be redelivered: topic={}, partition={}, " +
                            "offset={}, error={}",
                    record.topic(), record.partition(),
                    record.offset(), e.getMessage(), e);
            return;
        }

        // Reached only on success — all error paths return early above.
        acknowledgment.acknowledge();
    }

    private AuditEvent toEntity(AuditEventMessage message) {
        if (message.actorType() == ActorType.USER) {
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