package com.incidentplatform.incident.kafka;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.incident.domain.AuditEvent;
import com.incidentplatform.incident.repository.AuditEventRepository;
import com.incidentplatform.shared.audit.ActorType;
import com.incidentplatform.shared.dto.AuditEventMessage;
import com.incidentplatform.shared.kafka.DeadLetterPublisher;
import com.incidentplatform.shared.kafka.TenantKafkaRecordResolver;
import com.incidentplatform.shared.kafka.TenantResolutionException;
import com.incidentplatform.shared.security.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 *
 * <h2>Changed (backlog #0-84): only the two idempotency keys mean "already stored"</h2>
 * Producers with an audit outbox send at least once, so a resend arrives as a
 * new record with another offset; it is caught by
 * {@code uq_audit_events_tenant_event_id} (V13) on the event's own id.
 * Until then every {@link DataIntegrityViolationException} was acknowledged as
 * a duplicate, so a row that broke any other rule (a NOT NULL, a check, a
 * length) vanished with an INFO line (found in review). Now only a violation of
 * one of {@link #IDEMPOTENCY_KEYS} is a duplicate; any other is a poison pill,
 * rejected like unparseable JSON (a retry would fail the same way): logged at
 * ERROR, counted, alerted and sent to the dead-letter topic, since the
 * producer's outbox already counts it as delivered.
 */
@Component
public class AuditEventConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(AuditEventConsumer.class);

    /** The unique indexes whose violation means the event is already stored (V10, V13); public for the migration test. */
    public static final Set<String> IDEMPOTENCY_KEYS = Set.of(
            "uq_audit_events_kafka_partition_offset",
            "uq_audit_events_tenant_event_id");

    static final String REASON_UNREADABLE = "unreadable";
    static final String REASON_CONSTRAINT = "constraint";
    static final String REASON_TENANT_MISMATCH = "tenant_mismatch";
    /** Backlog #0-92: no valid tenant in the payload, or an invalid tenant header. */
    static final String REASON_TENANT_INVALID = "tenant_invalid";

    /** How long the consumer waits for the dead-letter copy of a rejected record. */
    static final Duration DEAD_LETTER_TIMEOUT = Duration.ofSeconds(10);
    /** When a record whose dead-letter copy failed comes again. */
    static final Duration DEAD_LETTER_RETRY = Duration.ofSeconds(5);

    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;
    private final DeadLetterPublisher deadLetterPublisher;
    private final TenantKafkaRecordResolver recordResolver;
    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> rejected = new ConcurrentHashMap<>();

    public AuditEventConsumer(AuditEventRepository auditEventRepository,
                              ObjectMapper objectMapper,
                              DeadLetterPublisher deadLetterPublisher,
                              TenantKafkaRecordResolver recordResolver,
                              MeterRegistry meterRegistry) {
        this.auditEventRepository = auditEventRepository;
        this.objectMapper = objectMapper;
        this.deadLetterPublisher = deadLetterPublisher;
        this.recordResolver = recordResolver;
        this.meterRegistry = meterRegistry;
        // Registered at zero now (backlog #0-84, found in the review of the
        // second step): a counter created at its first rejection starts its
        // series at 1, and AuditEventsRejected's increase() missed that first one.
        for (final String reason : List.of(REASON_UNREADABLE, REASON_CONSTRAINT, REASON_TENANT_MISMATCH,
                REASON_TENANT_INVALID)) {
            rejected.put(reason, rejectedCounter(reason));
        }
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
            final AuditEventMessage message;
            final String tenantId;
            try {
                // The tenant per record, as every consumer resolves it: since
                // backlog #0-92 the payload's, which the header must match
                // (TenantKafkaRecordResolver). This consumer used to compare
                // the two itself, the only one that did.
                final JsonNode payload = recordResolver.parseJson(record.value());
                tenantId = recordResolver.extractTenantId(record, payload);
                message = objectMapper.treeToValue(payload, AuditEventMessage.class);
            } catch (TenantResolutionException e) {
                // A record whose tenant cannot be trusted: stored under neither
                // the header's tenant nor the payload's (found in review of
                // #0-84). Its dead-letter entry names no tenant.
                rejectThenAcknowledge(record, null,
                        e.reason() == TenantResolutionException.Reason.MISMATCH
                                ? REASON_TENANT_MISMATCH : REASON_TENANT_INVALID,
                        "record tenant refused (" + e.reason().tag() + ")", acknowledgment);
                return;
            } catch (IOException | IllegalArgumentException e) {
                // Poison pill — unparseable JSON, no tenant, or a structurally
                // invalid payload. Retrying will never succeed.
                //
                // Changed (backlog #0-84, found in review): no longer only a log
                // line. Producers with an outbox mark an event sent once Kafka
                // has it, so a record skipped here is lost with no backlog to
                // show it: it goes to the dead-letter topic and is counted.
                rejectThenAcknowledge(record, null, REASON_UNREADABLE, unreadableReason(e), acknowledgment);
                return;
            }
            TenantContext.set(tenantId);

            try {
                auditEventRepository.save(toEntity(message, tenantId, record));
            } catch (DataIntegrityViolationException e) {
                final String constraint = violatedConstraint(e);
                if (constraint != null && IDEMPOTENCY_KEYS.contains(constraint)) {
                    // Fixed (backlog #37): uq_audit_events_kafka_partition_offset
                    // (V10) — this exact (partition, offset) is stored, a Kafka
                    // redelivery after a crash between save() and acknowledge().
                    // Backlog #0-84: or uq_audit_events_tenant_event_id (V13) —
                    // the producer's outbox relay sent the same event again as a
                    // new record. Either way already processed: acknowledge, as a
                    // successful save would.
                    log.info("Audit event already stored (Kafka redelivery or outbox resend, {}) — "
                                    + "acknowledging without re-inserting: topic={}, partition={}, offset={}",
                            constraint, record.topic(), record.partition(), record.offset());
                    acknowledgment.acknowledge();
                } else {
                    // Backlog #0-84 (found in review): any other rule the row
                    // breaks fails the same way on every retry — a poison pill.
                    rejectThenAcknowledge(record, tenantId, REASON_CONSTRAINT, constraintReason(e, constraint),
                            acknowledgment);
                }
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

            log.debug("Audit event saved: eventType={}, incidentId={}, tenant={}",
                    message.eventType(), message.resourceId(), tenantId);
            acknowledgment.acknowledge();
        } finally {
            TenantContext.clear();
        }
    }

    private Counter rejectedCounter(String reason) {
        return Counter.builder("audit.events.rejected")
                .description("Audit records the consumer could not store, sent to the dead-letter topic "
                        + "(backlog #0-84)")
                .tag("reason", reason)
                .register(meterRegistry);
    }

    /**
     * A record that can never be stored: logged, sent to the dead-letter topic
     * for a person to look at (as incident events are, {@code IncidentKafkaConsumer}),
     * counted ({@code audit.events.rejected}, alert {@code AuditEventsRejected})
     * and only then acknowledged.
     *
     * <p>The dead-letter send is awaited (found in review): the producer's
     * outbox already counts the event delivered, so acknowledging before the
     * copy is safe would lose it on a failed send with only a log line. If
     * Kafka does not take the copy, the record is not acknowledged but
     * {@code nack}ed: the partition is sought back to it and it comes again
     * after {@link #DEAD_LETTER_RETRY}, while consumer lag shows the stall.
     */
    private void rejectThenAcknowledge(ConsumerRecord<String, String> record, String tenantId, String reason,
                                       String error, Acknowledgment acknowledgment) {
        log.error("Audit event rejected ({}) — NOT stored, sending it to the dead-letter topic: topic={}, "
                        + "partition={}, offset={}, tenant={}, error={}",
                reason, record.topic(), record.partition(), record.offset(), tenantId, error);
        try {
            deadLetterPublisher.publishAndWait(record.value(), record.topic(),
                    tenantId, reason + ": " + error, DEAD_LETTER_TIMEOUT);
        } catch (RuntimeException e) {
            log.error("Rejected audit event could not be dead-lettered — not acknowledged, retried in {}: "
                            + "topic={}, partition={}, offset={}", DEAD_LETTER_RETRY,
                    record.topic(), record.partition(), record.offset(), e);
            acknowledgment.nack(DEAD_LETTER_RETRY);
            return;
        }
        rejected.computeIfAbsent(reason, this::rejectedCounter).increment();
        acknowledgment.acknowledge();
    }

    /**
     * Why a record broke a constraint, without the database's message (found in
     * review): Postgres adds {@code DETAIL: Failing row contains (...)}, the
     * row's values, which must not reach the logs or the dead-letter reason.
     * The record itself is in the dead-letter entry for whoever investigates.
     */
    static String constraintReason(DataIntegrityViolationException e, String constraint) {
        String sqlState = null;
        for (Throwable cause = e; cause != null && sqlState == null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                sqlState = sql.getSQLState();
            }
        }
        return "violates " + (constraint != null ? constraint : "a database constraint")
                + (sqlState != null ? " (SQLState " + sqlState + ")" : "");
    }

    /**
     * Why a record could not be read, without the parser's message, which
     * quotes the payload (found in review): the JSON error's type and position,
     * or the resolver's own content-free message for a record without a tenant.
     */
    static String unreadableReason(Exception e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof JsonProcessingException json) {
                final JsonLocation at = json.getLocation();
                return "invalid JSON (" + json.getClass().getSimpleName()
                        + (at != null ? " at line " + at.getLineNr() + ", column " + at.getColumnNr() : "") + ")";
            }
        }
        return e.getClass().getSimpleName();
    }

    /**
     * The violated constraint's name, as Hibernate extracts it from
     * Postgres's error; {@code null} when the cause names none.
     */
    public static String violatedConstraint(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                return violation.getConstraintName().toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private AuditEvent toEntity(AuditEventMessage message, String tenantId,
                                ConsumerRecord<String, String> record) {
        if (message.actorType() == ActorType.USER) {
            return AuditEvent.user(
                    message.resourceId(),
                    tenantId,
                    message.eventType(),
                    message.sourceService(),
                    message.actor(),
                    message.detail(),
                    message.metadata(),
                    record.partition(),
                    record.offset(),
                    message.eventId(),
                    message.occurredAt()
            );
        } else {
            return AuditEvent.system(
                    message.resourceId(),
                    tenantId,
                    message.eventType(),
                    message.sourceService(),
                    message.detail(),
                    message.metadata(),
                    record.partition(),
                    record.offset(),
                    message.eventId(),
                    message.occurredAt()
            );
        }
    }
}