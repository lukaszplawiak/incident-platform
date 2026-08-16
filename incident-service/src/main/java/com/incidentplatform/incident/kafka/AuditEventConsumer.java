package com.incidentplatform.incident.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.incident.domain.AuditEvent;
import com.incidentplatform.incident.repository.AuditEventRepository;
import com.incidentplatform.shared.audit.ActorType;
import com.incidentplatform.shared.dto.AuditEventMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * <h2>Fixed: redelivery could duplicate an audit event (backlog #37)</h2>
 * {@code toEntity(...)} previously built an {@link AuditEvent} with a
 * fresh, randomly-generated primary key on every call — so if this
 * consumer crashed after a successful {@code save()} but before
 * {@link Acknowledgment#acknowledge()}, Kafka's at-least-once redelivery
 * of the same message would insert a second, indistinguishable duplicate
 * row.
 *
 * <p>Fixed with the standard, production-proven pattern for exactly this
 * gap (the same one Kafka Connect JDBC Sink connectors use): derive a
 * deterministic idempotency key from the message's own Kafka coordinates
 * — {@code (partition, offset)}, permanently unique per message on a
 * topic, at zero extra cost from the producer side — and enforce
 * uniqueness on it at the database level (migration V10). A
 * {@link DataIntegrityViolationException} on that constraint is no longer
 * treated as a transient error (which previously would have left the
 * message unacknowledged, triggering an infinite redelivery loop against
 * the same conflict); it's now recognized as "already processed" and
 * acknowledged normally. Same underlying pattern — a DB uniqueness
 * constraint doing the deduplication, application code treating the
 * resulting exception as an expected outcome, not a failure — already
 * used by oncall-service's {@code excl_oncall_schedule_overlap} constraint.
 */
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

            final AuditEvent auditEvent = toEntity(message, record);
            auditEventRepository.save(auditEvent);

            log.debug("Audit event saved: eventType={}, incidentId={}, " +
                            "tenant={}", message.eventType(), message.resourceId(),
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

        } catch (DataIntegrityViolationException e) {
            // Fixed (backlog #37): the uq_audit_events_kafka_partition_offset
            // constraint (migration V10) rejected this insert — meaning a
            // row for this exact (partition, offset) already exists, i.e.
            // this message was already successfully processed and this is
            // a Kafka redelivery (consumer crashed after save() but before
            // acknowledge() last time). Not an error — acknowledge and move
            // on, same as a successful save would.
            log.info("Audit event already processed (Kafka redelivery) — " +
                            "acknowledging without re-inserting: " +
                            "topic={}, partition={}, offset={}",
                    record.topic(), record.partition(), record.offset());
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

    private AuditEvent toEntity(AuditEventMessage message,
                                ConsumerRecord<String, String> record) {
        if (message.actorType() == ActorType.USER) {
            return AuditEvent.user(
                    message.resourceId(),
                    message.tenantId(),
                    message.eventType(),
                    message.sourceService(),
                    message.actor(),
                    message.detail(),
                    message.metadata(),
                    record.partition(),
                    record.offset()
            );
        } else {
            return AuditEvent.system(
                    message.resourceId(),
                    message.tenantId(),
                    message.eventType(),
                    message.sourceService(),
                    message.detail(),
                    message.metadata(),
                    record.partition(),
                    record.offset()
            );
        }
    }
}