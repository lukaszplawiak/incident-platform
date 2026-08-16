package com.incidentplatform.incident.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.incidentplatform.incident.domain.AuditEvent;
import com.incidentplatform.incident.repository.AuditEventRepository;
import com.incidentplatform.shared.audit.ActorType;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.dto.AuditEventMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.support.Acknowledgment;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditEventConsumer")
class AuditEventConsumerTest {

    @Mock
    private AuditEventRepository auditEventRepository;

    @Mock
    private Acknowledgment acknowledgment;

    private AuditEventConsumer consumer;
    private ObjectMapper objectMapper;

    private static final String TOPIC = "audit.events";
    private static final String TENANT_ID = "acme-corp";
    private static final UUID INCIDENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        consumer = new AuditEventConsumer(auditEventRepository, objectMapper);
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private ConsumerRecord<String, String> buildRecord(String payload) {
        return new ConsumerRecord<>(TOPIC, 0, 0L, TENANT_ID, payload);
    }

    private String buildAuditEventJson() throws Exception {
        final AuditEventMessage message = AuditEventMessage.incident(
                INCIDENT_ID,
                TENANT_ID,
                AuditEventTypes.INCIDENT_OPENED,
                "incident-service",
                "Incident opened by ingestion pipeline",
                Map.of("severity", "CRITICAL")
        );
        return objectMapper.writeValueAsString(message);
    }

    private String buildUserAuditEventJson() throws Exception {
        final AuditEventMessage message = AuditEventMessage.incidentUser(
                INCIDENT_ID,
                TENANT_ID,
                AuditEventTypes.INCIDENT_ACKNOWLEDGED,
                "incident-service",
                UUID.randomUUID().toString(),
                "Incident acknowledged by operator",
                Map.of()
        );
        return objectMapper.writeValueAsString(message);
    }

    // ─── success paths ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("successful processing")
    class SuccessfulProcessing {

        @Test
        @DisplayName("should save system audit event and acknowledge")
        void shouldSaveSystemAuditEventAndAcknowledge() throws Exception {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(buildAuditEventJson());

            // when
            consumer.consume(record, acknowledgment);

            // then
            then(auditEventRepository).should().save(any(AuditEvent.class));
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("should save user audit event and acknowledge")
        void shouldSaveUserAuditEventAndAcknowledge() throws Exception {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(buildUserAuditEventJson());

            // when
            consumer.consume(record, acknowledgment);

            // then
            then(auditEventRepository).should().save(any(AuditEvent.class));
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("should map system event to AuditEvent with correct tenantId")
        void shouldMapSystemEventWithCorrectTenantId() throws Exception {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(buildAuditEventJson());

            final ArgumentCaptor<AuditEvent> captor =
                    ArgumentCaptor.forClass(AuditEvent.class);

            // when
            consumer.consume(record, acknowledgment);

            // then
            then(auditEventRepository).should().save(captor.capture());
            assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_ID);
            assertThat(captor.getValue().getIncidentId()).isEqualTo(INCIDENT_ID);
        }

        @Test
        @DisplayName("should map USER actor type to user AuditEvent")
        void shouldMapUserActorTypeCorrectly() throws Exception {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(buildUserAuditEventJson());

            final ArgumentCaptor<AuditEvent> captor =
                    ArgumentCaptor.forClass(AuditEvent.class);

            // when
            consumer.consume(record, acknowledgment);

            // then
            then(auditEventRepository).should().save(captor.capture());
            assertThat(captor.getValue().getActorType()).isEqualTo(ActorType.USER);
        }

        /**
         * Regression coverage for backlog #37's fix: the built entity must
         * carry the record's real (partition, offset) — the idempotency
         * key migration V10's unique constraint enforces. Uses a non-zero
         * partition/offset specifically so this test can't accidentally
         * pass due to unset/default int fields both happening to be 0.
         */
        @Test
        @DisplayName("should populate kafkaPartition/kafkaOffset from the ConsumerRecord")
        void shouldPopulateKafkaIdempotencyKeyFromRecord() throws Exception {
            // given
            final ConsumerRecord<String, String> record = new ConsumerRecord<>(
                    TOPIC, 3, 42L, TENANT_ID, buildAuditEventJson());

            final ArgumentCaptor<AuditEvent> captor =
                    ArgumentCaptor.forClass(AuditEvent.class);

            // when
            consumer.consume(record, acknowledgment);

            // then
            then(auditEventRepository).should().save(captor.capture());
            assertThat(captor.getValue().getKafkaPartition()).isEqualTo(3);
            assertThat(captor.getValue().getKafkaOffset()).isEqualTo(42L);
        }
    }

    // ─── idempotent redelivery (backlog #37) ───────────────────────────────

    @Nested
    @DisplayName("idempotent redelivery handling")
    class IdempotentRedeliveryHandling {

        /**
         * The actual regression test for backlog #37. Simulates exactly
         * the scenario the fix targets: the consumer crashed after a
         * successful save() but before acknowledge() last time, so Kafka
         * redelivers the identical message. The second save() attempt now
         * hits uq_audit_events_kafka_partition_offset and Spring Data
         * translates the resulting SQL constraint violation into
         * DataIntegrityViolationException — this must be treated as
         * "already processed" (acknowledge, no error), not as a transient
         * failure (which would leave it unacknowledged and cause Kafka to
         * redeliver the same message again, forever, against the same
         * unresolvable conflict).
         */
        @Test
        @DisplayName("acknowledges (does not error) when save fails on the " +
                "unique (partition, offset) constraint — a Kafka redelivery")
        void acknowledgesOnDuplicateKeyConstraintViolation() throws Exception {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(buildAuditEventJson());

            willThrow(new DataIntegrityViolationException(
                    "duplicate key value violates unique constraint " +
                            "\"uq_audit_events_kafka_partition_offset\""))
                    .given(auditEventRepository).save(any());

            // when
            consumer.consume(record, acknowledgment);

            // then — acknowledged, exactly as a successful save would be,
            // NOT treated as a transient error
            then(acknowledgment).should().acknowledge();
        }
    }

    // ─── poison pill ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("poison pill handling")
    class PoisonPillHandling {

        @Test
        @DisplayName("should acknowledge and skip unparseable JSON — never blocks partition")
        void shouldAcknowledgeOnUnparseableJson() {
            // given — invalid JSON: objectMapper.readValue() throws IOException
            // wrapped as poison pill → acknowledge + skip
            final ConsumerRecord<String, String> record =
                    buildRecord("{ not valid json }");

            // when
            consumer.consume(record, acknowledgment);

            // then — acknowledged so partition is not blocked
            then(acknowledgment).should().acknowledge();
            then(auditEventRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("should acknowledge and skip completely malformed payload")
        void shouldAcknowledgeOnMalformedPayload() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord("not-json-at-all");

            // when
            consumer.consume(record, acknowledgment);

            // then
            then(acknowledgment).should().acknowledge();
            then(auditEventRepository).should(never()).save(any());
        }
    }

    // ─── transient errors ────────────────────────────────────────────────────

    @Nested
    @DisplayName("transient error handling")
    class TransientErrorHandling {

        @Test
        @DisplayName("should NOT acknowledge when DB save throws — Kafka will redeliver")
        void shouldNotAcknowledgeOnDbFailure() throws Exception {
            // given — DB unavailable: save() throws RuntimeException (transient)
            final ConsumerRecord<String, String> record =
                    buildRecord(buildAuditEventJson());

            willThrow(new RuntimeException("DB connection lost"))
                    .given(auditEventRepository).save(any());

            // when
            consumer.consume(record, acknowledgment);

            // then — NOT acknowledged so Kafka redelivers after consumer restart.
            // At-least-once delivery for audit events prevents permanent gaps
            // in the audit trail when the DB is temporarily unavailable.
            then(acknowledgment).should(never()).acknowledge();
        }

        @Test
        @DisplayName("should NOT acknowledge on any unexpected transient exception")
        void shouldNotAcknowledgeOnUnexpectedTransientException() throws Exception {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(buildAuditEventJson());

            willThrow(new RuntimeException("connection pool exhausted"))
                    .given(auditEventRepository).save(any());

            // when
            consumer.consume(record, acknowledgment);

            // then
            then(acknowledgment).should(never()).acknowledge();
        }
    }
}