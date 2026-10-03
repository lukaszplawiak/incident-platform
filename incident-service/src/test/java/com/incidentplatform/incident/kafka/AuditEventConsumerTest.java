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
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import com.incidentplatform.shared.kafka.DeadLetterPublisher;
import com.incidentplatform.shared.kafka.TenantKafkaProducerInterceptor;
import com.incidentplatform.shared.kafka.TenantKafkaRecordResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

    @Mock
    private DeadLetterPublisher deadLetterPublisher;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private AuditEventConsumer consumer;
    private ObjectMapper objectMapper;

    private static final String TOPIC = "audit.events";
    private static final String TENANT_ID = "acme-corp";
    private static final UUID INCIDENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        consumer = new AuditEventConsumer(auditEventRepository, objectMapper, deadLetterPublisher,
                new TenantKafkaRecordResolver(objectMapper, new SimpleMeterRegistry()), meters);
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    /** A record as every sender builds it (TenantRecords): the header copies the payload's tenant. */
    private ConsumerRecord<String, String> buildRecord(String payload) {
        final ConsumerRecord<String, String> record = buildRecordWithoutHeader(payload);
        record.headers().add(TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                TENANT_ID.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private ConsumerRecord<String, String> buildRecordWithoutHeader(String payload) {
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
            record.headers().add(TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                    TENANT_ID.getBytes(StandardCharsets.UTF_8));

            final ArgumentCaptor<AuditEvent> captor =
                    ArgumentCaptor.forClass(AuditEvent.class);

            // when
            consumer.consume(record, acknowledgment);

            // then
            then(auditEventRepository).should().save(captor.capture());
            assertThat(captor.getValue().getKafkaPartition()).isEqualTo(3);
            assertThat(captor.getValue().getKafkaOffset()).isEqualTo(42L);
        }

        @Test
        @DisplayName("stores the producer's event id and the time the event happened, not the consume time "
                + "(backlog #0-84: the outbox can deliver late)")
        void storesEventIdAndProducerTime() throws Exception {
            final AuditEventMessage message = AuditEventMessage.incident(
                    INCIDENT_ID, TENANT_ID, AuditEventTypes.INCIDENT_CREATED,
                    "incident-service", "Created", Map.of());
            final AuditEventMessage late = new AuditEventMessage(message.resourceId(), message.resourceType(),
                    message.tenantId(), message.eventType(), message.actor(), message.actorType(),
                    message.sourceService(), message.detail(), message.metadata(),
                    java.time.Instant.parse("2026-10-03T09:00:00Z"), message.eventId());
            final ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);

            consumer.consume(buildRecord(objectMapper.writeValueAsString(late)), acknowledgment);

            then(auditEventRepository).should().save(captor.capture());
            assertThat(captor.getValue().getEventId()).isEqualTo(message.eventId());
            assertThat(captor.getValue().getOccurredAt()).isEqualTo(java.time.Instant.parse("2026-10-03T09:00:00Z"));
        }

        @Test
        @DisplayName("a record from a producer before the outbox (no event id) is still stored")
        void storesLegacyRecordWithoutEventId() throws Exception {
            final String legacy = "{\"resourceId\":\"" + INCIDENT_ID + "\",\"resourceType\":\"INCIDENT\","
                    + "\"tenantId\":\"" + TENANT_ID + "\",\"eventType\":\"INCIDENT_CREATED\","
                    + "\"actor\":\"incident-service\",\"actorType\":\"SYSTEM\","
                    + "\"sourceService\":\"incident-service\",\"detail\":\"x\",\"metadata\":{}}";
            final ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);

            consumer.consume(buildRecord(legacy), acknowledgment);

            then(auditEventRepository).should().save(captor.capture());
            assertThat(captor.getValue().getEventId()).isNull();
            assertThat(captor.getValue().getOccurredAt()).as("falls back to now").isNotNull();
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

            willThrow(violation("uq_audit_events_kafka_partition_offset"))
                    .given(auditEventRepository).save(any());

            // when
            final ListAppender<ILoggingEvent> logs = captureLogs();
            try {
                consumer.consume(record, acknowledgment);

                // then — acknowledged, exactly as a successful save would be,
                // NOT treated as a transient error, and not rejected
                then(acknowledgment).should().acknowledge();
                assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.ERROR);
                then(deadLetterPublisher).shouldHaveNoInteractions();
            } finally {
                release(logs);
            }
        }

        @Test
        @DisplayName("acknowledges an outbox resend: the same event id again (backlog #0-84)")
        void acknowledgesOutboxResend() throws Exception {
            willThrow(violation("uq_audit_events_tenant_event_id"))
                    .given(auditEventRepository).save(any());

            consumer.consume(buildRecord(buildAuditEventJson()), acknowledgment);

            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("any other constraint is a poison pill: skipped, logged at ERROR, not a duplicate (backlog #0-84)")
        void otherConstraintIsPoisonPill() throws Exception {
            willThrow(violation("chk_audit_actor_type"))
                    .given(auditEventRepository).save(any());

            final ListAppender<ILoggingEvent> logs = captureLogs();
            try {
                consumer.consume(buildRecord(buildAuditEventJson()), acknowledgment);

                then(acknowledgment).should().acknowledge();
                assertThat(logs.list).anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage()).contains("chk_audit_actor_type").contains("NOT stored");
                });
                then(deadLetterPublisher).should().publishAndWait(any(String.class), eq(TOPIC), eq(TENANT_ID),
                        org.mockito.ArgumentMatchers.contains("chk_audit_actor_type"),
                        eq(AuditEventConsumer.DEAD_LETTER_TIMEOUT));
                assertThat(meters.counter("audit.events.rejected", "reason", "constraint").count()).isEqualTo(1.0);
            } finally {
                release(logs);
            }
        }

        @Test
        @DisplayName("a violation that names no constraint is a poison pill too")
        void unnamedViolation() {
            assertThat(AuditEventConsumer.violatedConstraint(
                    new DataIntegrityViolationException("value too long"))).isNull();
            assertThat(AuditEventConsumer.violatedConstraint(violation("UQ_AUDIT_EVENTS_TENANT_EVENT_ID")))
                    .as("Postgres folds names to lower case; compared the same way")
                    .isEqualTo("uq_audit_events_tenant_event_id");
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
            // Backlog #0-84: not just a log line — dead-lettered and counted.
            then(deadLetterPublisher).should().publishAndWait(any(String.class), eq(TOPIC), isNull(),
                    org.mockito.ArgumentMatchers.startsWith("unreadable"), eq(AuditEventConsumer.DEAD_LETTER_TIMEOUT));
            assertThat(meters.counter("audit.events.rejected", "reason", "unreadable").count()).isEqualTo(1.0);
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
            // Backlog #0-84: not just a log line — dead-lettered and counted.
            then(deadLetterPublisher).should().publishAndWait(any(String.class), eq(TOPIC), isNull(),
                    org.mockito.ArgumentMatchers.startsWith("unreadable"), eq(AuditEventConsumer.DEAD_LETTER_TIMEOUT));
            assertThat(meters.counter("audit.events.rejected", "reason", "unreadable").count()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("tenant per record and the dead-letter copy (backlog #0-84)")
    class TenantAndDeadLetter {

        private ConsumerRecord<String, String> withHeader(String payload, String tenant) {
            final ConsumerRecord<String, String> record = buildRecordWithoutHeader(payload);
            record.headers().add(TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                    tenant.getBytes(StandardCharsets.UTF_8));
            return record;
        }

        @Test
        @DisplayName("a record without X-Tenant-Id is rejected, not stored, even with a valid payload tenant "
                + "(backlog #0-92)")
        void missingHeader() throws Exception {
            consumer.consume(buildRecordWithoutHeader(buildAuditEventJson()), acknowledgment);

            then(auditEventRepository).should(never()).save(any());
            then(deadLetterPublisher).should().publishAndWait(any(String.class), eq(TOPIC), isNull(),
                    eq("tenant_invalid: record tenant refused (header_missing)"),
                    eq(AuditEventConsumer.DEAD_LETTER_TIMEOUT));
            assertThat(meters.counter("audit.events.rejected", "reason", "tenant_invalid").count()).isEqualTo(1.0);
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("the header's tenant is stored and set while the record is handled, then cleared")
        void headerTenant() throws Exception {
            final java.util.concurrent.atomic.AtomicReference<String> during =
                    new java.util.concurrent.atomic.AtomicReference<>();
            org.mockito.BDDMockito.given(auditEventRepository.save(any())).willAnswer(i -> {
                during.set(com.incidentplatform.shared.security.TenantContext.getOrNull());
                return i.getArgument(0);
            });

            consumer.consume(withHeader(buildAuditEventJson(), TENANT_ID), acknowledgment);

            final ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
            then(auditEventRepository).should().save(saved.capture());
            assertThat(saved.getValue().getTenantId()).isEqualTo(TENANT_ID);
            assertThat(during.get()).isEqualTo(TENANT_ID);
            assertThat(com.incidentplatform.shared.security.TenantContext.getOrNull()).isNull();
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("a header tenant that differs from the payload's is rejected, not stored under either")
        void tenantMismatch() throws Exception {
            consumer.consume(withHeader(buildAuditEventJson(), "globex"), acknowledgment);

            then(auditEventRepository).should(never()).save(any());
            then(deadLetterPublisher).should().publishAndWait(any(String.class), eq(TOPIC), isNull(),
                    org.mockito.ArgumentMatchers.startsWith("tenant_mismatch"),
                    eq(AuditEventConsumer.DEAD_LETTER_TIMEOUT));
            assertThat(meters.counter("audit.events.rejected", "reason", "tenant_mismatch").count()).isEqualTo(1.0);
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("an unreadable record is dead-lettered with no tenant: its header alone is not trusted "
                + "(backlog #0-92)")
        void unreadableHasNoTenant() {
            consumer.consume(withHeader("not-json", TENANT_ID), acknowledgment);

            then(deadLetterPublisher).should().publishAndWait(eq("not-json"), eq(TOPIC), isNull(),
                    org.mockito.ArgumentMatchers.startsWith("unreadable"), eq(AuditEventConsumer.DEAD_LETTER_TIMEOUT));
        }

        @Test
        @DisplayName("a payload naming no tenant is rejected: the header alone does not choose the trail")
        void payloadWithoutTenant() {
            final String noTenant = "{\"resourceId\":\"" + INCIDENT_ID + "\",\"resourceType\":\"INCIDENT\","
                    + "\"eventType\":\"INCIDENT_CREATED\",\"actor\":\"incident-service\",\"actorType\":\"SYSTEM\","
                    + "\"sourceService\":\"incident-service\",\"occurredAt\":\"2026-10-02T10:00:00Z\"}";

            consumer.consume(withHeader(noTenant, TENANT_ID), acknowledgment);

            then(auditEventRepository).should(never()).save(any());
            then(deadLetterPublisher).should().publishAndWait(any(String.class), eq(TOPIC), isNull(),
                    eq("tenant_invalid: record tenant refused (missing)"), eq(AuditEventConsumer.DEAD_LETTER_TIMEOUT));
            assertThat(meters.counter("audit.events.rejected", "reason", "tenant_invalid").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("an invalid tenant header is rejected, never quoted (backlog #0-92)")
        void invalidTenantHeader() throws Exception {
            consumer.consume(withHeader(buildAuditEventJson(), "evil\nFAKE LOG LINE"), acknowledgment);

            then(auditEventRepository).should(never()).save(any());
            then(deadLetterPublisher).should().publishAndWait(any(String.class), eq(TOPIC), isNull(),
                    eq("tenant_invalid: record tenant refused (invalid)"), eq(AuditEventConsumer.DEAD_LETTER_TIMEOUT));
        }

        @Test
        @DisplayName("the reason names the constraint and SQLState, never the database's row text (found in review)")
        void reasonWithoutRowContent() throws Exception {
            willThrow(new DataIntegrityViolationException("could not execute statement",
                    new org.hibernate.exception.ConstraintViolationException("check failed",
                            new java.sql.SQLException("ERROR: new row violates check constraint "
                                    + "\"chk_audit_actor_type\" Detail: Failing row contains (acme, top-secret)", "23514"),
                            "chk_audit_actor_type")))
                    .given(auditEventRepository).save(any());

            final ListAppender<ILoggingEvent> logs = captureLogs();
            try {
                consumer.consume(buildRecord(buildAuditEventJson()), acknowledgment);

                then(deadLetterPublisher).should().publishAndWait(any(String.class), eq(TOPIC), eq(TENANT_ID),
                        eq("constraint: violates chk_audit_actor_type (SQLState 23514)"),
                        eq(AuditEventConsumer.DEAD_LETTER_TIMEOUT));
                assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("top-secret"));
            } finally {
                release(logs);
            }
        }

        @Test
        @DisplayName("an unreadable record's reason gives the JSON error's type and place, not the payload")
        void unreadableReasonWithoutPayload() {
            consumer.consume(withHeader("{\"tenantId\": top-secret}", TENANT_ID), acknowledgment);

            final ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
            then(deadLetterPublisher).should().publishAndWait(any(String.class), eq(TOPIC), isNull(),
                    reason.capture(), any());
            assertThat(reason.getValue()).startsWith("unreadable: invalid JSON (").contains("line 1")
                    .doesNotContain("top-secret");
        }

        @Test
        @DisplayName("a dead-letter copy Kafka did not take: not acknowledged, nacked to come again, not counted")
        void deadLetterFailureNacks() {
            org.mockito.BDDMockito.willThrow(new IllegalStateException("broker down")).given(deadLetterPublisher)
                    .publishAndWait(anyString(), anyString(), any(), anyString(), any());

            consumer.consume(buildRecord("not-json"), acknowledgment);

            then(acknowledgment).should().nack(AuditEventConsumer.DEAD_LETTER_RETRY);
            then(acknowledgment).should(never()).acknowledge();
            assertThat(meters.find("audit.events.rejected").counters())
                    .allSatisfy(counter -> assertThat(counter.count()).isZero());
        }

        /**
         * Found in the review of #0-84's second step: a counter created at its
         * first rejection starts its series at 1, which AuditEventsRejected's
         * increase() cannot see.
         */
        @Test
        @DisplayName("every rejection reason's counter exists at zero before the first rejection")
        void rejectedCountersRegisteredAtZero() {
            assertThat(meters.find("audit.events.rejected").counters())
                    .extracting(counter -> counter.getId().getTag("reason"))
                    .containsExactlyInAnyOrder("unreadable", "constraint", "tenant_mismatch", "tenant_invalid");
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

    /** As Spring translates Hibernate's exception for a violated Postgres constraint. */
    private static DataIntegrityViolationException violation(String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
                new org.hibernate.exception.ConstraintViolationException("violates " + constraint,
                        new java.sql.SQLException("violates " + constraint, "23505"), constraint));
    }

    private static ListAppender<ILoggingEvent> captureLogs() {
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(AuditEventConsumer.class)).addAppender(appender);
        return appender;
    }

    private static void release(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(AuditEventConsumer.class)).detachAppender(appender);
        appender.stop();
    }
}
