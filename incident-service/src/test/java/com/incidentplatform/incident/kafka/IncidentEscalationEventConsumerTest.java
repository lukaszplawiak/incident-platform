package com.incidentplatform.incident.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.incident.domain.Incident;
import com.incidentplatform.incident.service.IncidentCommandService;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.events.IncidentEventTypes;
import com.incidentplatform.shared.events.SourceType;
import com.incidentplatform.shared.kafka.TenantKafkaProducerInterceptor;
import com.incidentplatform.shared.kafka.DeadLetterPublisher;
import com.incidentplatform.shared.kafka.TenantKafkaRecordResolver;
import com.incidentplatform.shared.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;

/**
 * Previously had no test file at all. Added primarily to cover backlog
 * #40's fix — see {@link IncidentEscalationEventConsumer}'s own Javadoc
 * for the full account of the logging-precision improvement being
 * regression-tested here — but also gives this class its first baseline
 * coverage for the rest of its behavior.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IncidentEscalationEventConsumer")
class IncidentEscalationEventConsumerTest {

    @Mock private IncidentCommandService commandService;
    @Mock private Acknowledgment acknowledgment;
    @Mock private DeadLetterPublisher deadLetterPublisher;

    private IncidentEscalationEventConsumer consumer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TOPIC = "incidents.lifecycle";
    private static final String TENANT_ID = "acme-corp";

    @BeforeEach
    void setUp() {
        // Fixed (backlog #75): extractTenantId/parseJson moved to the
        // shared TenantKafkaRecordResolver — objectMapper is no longer
        // passed to the consumer directly, only used to build this.
        consumer = new IncidentEscalationEventConsumer(
                commandService, new TenantKafkaRecordResolver(objectMapper, new SimpleMeterRegistry()),
                deadLetterPublisher);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Incident buildIncident() {
        return new Incident(
                TENANT_ID, "High CPU usage", "CPU exceeded 95%",
                Severity.CRITICAL, SourceType.OPS, "prometheus",
                "prometheus:highcpu:server-1", UUID.randomUUID(), Instant.now());
    }

    private ConsumerRecord<String, String> buildEscalatedRecord(UUID incidentId,
                                                                int escalationLevel) {
        final String payload = String.format(
                "{\"incidentId\":\"%s\",\"tenantId\":\"%s\",\"escalationLevel\":%d}",
                incidentId, TENANT_ID, escalationLevel);
        final ConsumerRecord<String, String> record =
                new ConsumerRecord<>(TOPIC, 0, 0L, incidentId.toString(), payload);
        record.headers().add(new RecordHeader(IncidentEventTypes.HEADER_NAME,
                IncidentEventTypes.INCIDENT_ESCALATED.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(
                TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                TENANT_ID.getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    /** A record whose escalationLevel is the given raw JSON fragment, or absent when null. */
    private ConsumerRecord<String, String> buildRawLevelRecord(UUID incidentId, String rawLevel) {
        final String levelField = rawLevel == null ? "" : ",\"escalationLevel\":" + rawLevel;
        final String payload = String.format("{\"incidentId\":\"%s\",\"tenantId\":\"%s\"%s}",
                incidentId, TENANT_ID, levelField);
        final ConsumerRecord<String, String> record =
                new ConsumerRecord<>(TOPIC, 0, 0L, incidentId.toString(), payload);
        record.headers().add(new RecordHeader(IncidentEventTypes.HEADER_NAME,
                IncidentEventTypes.INCIDENT_ESCALATED.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(
                TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                TENANT_ID.getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    private void assertDeadLettered(ConsumerRecord<String, String> record) {
        then(commandService).shouldHaveNoInteractions();
        then(deadLetterPublisher).should().deadLetterThenAcknowledge(eq(record), eq(TENANT_ID),
                anyString(), eq(acknowledgment));
        then(acknowledgment).shouldHaveNoInteractions();
    }

    @Nested
    @DisplayName("escalationLevel validation (backlog #0-7)")
    class EscalationLevelValidation {

        @Test
        @DisplayName("dead-letters an event without escalationLevel")
        void deadLettersMissingEscalationLevel() {
            final ConsumerRecord<String, String> record = buildRawLevelRecord(UUID.randomUUID(), null);

            consumer.consumeIncidentEvent(record, acknowledgment);

            assertDeadLettered(record);
        }

        @ParameterizedTest
        @ValueSource(strings = {"null", "\"2\"", "\"x\"", "1.5"})
        @DisplayName("dead-letters a null, text or fractional escalationLevel")
        void deadLettersMalformedEscalationLevel(String raw) {
            final ConsumerRecord<String, String> record = buildRawLevelRecord(UUID.randomUUID(), raw);

            consumer.consumeIncidentEvent(record, acknowledgment);

            assertDeadLettered(record);
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "-1", "3"})
        @DisplayName("dead-letters an escalationLevel outside 1..2")
        void deadLettersOutOfRangeEscalationLevel(String raw) {
            final ConsumerRecord<String, String> record = buildRawLevelRecord(UUID.randomUUID(), raw);

            consumer.consumeIncidentEvent(record, acknowledgment);

            assertDeadLettered(record);
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 2})
        @DisplayName("records a valid escalationLevel, then acknowledges")
        void recordsValidEscalationLevel(int level) {
            final UUID incidentId = UUID.randomUUID();
            final ConsumerRecord<String, String> record = buildRawLevelRecord(incidentId, String.valueOf(level));
            given(commandService.recordEscalationLevel(incidentId, TENANT_ID, level)).willReturn(true);

            consumer.consumeIncidentEvent(record, acknowledgment);

            final InOrder order = inOrder(commandService, acknowledgment);
            order.verify(commandService).recordEscalationLevel(incidentId, TENANT_ID, level);
            order.verify(acknowledgment).acknowledge();
            then(deadLetterPublisher).shouldHaveNoInteractions();
        }

        @ParameterizedTest
        @ValueSource(strings = {"\"level-secret-value\"", "987654321"})
        @DisplayName("the reason of an invalid level never quotes the record's value")
        void invalidLevelReasonDoesNotQuoteValue(String raw) {
            final ConsumerRecord<String, String> record = buildRawLevelRecord(UUID.randomUUID(), raw);

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(deadLetterPublisher).should().deadLetterThenAcknowledge(eq(record), eq(TENANT_ID),
                    argThat(reason -> reason.startsWith("IllegalArgumentException at ")
                            && !reason.contains("level-secret-value")
                            && !reason.contains("987654321")),
                    eq(acknowledgment));
        }
    }

    @Nested
    @DisplayName("successful processing")
    class SuccessfulProcessing {

        @Test
        @DisplayName("records the escalation level and acknowledges")
        void recordsEscalationLevel() {
            final Incident incident = buildIncident();
            final ConsumerRecord<String, String> record =
                    buildEscalatedRecord(incident.getId(), 2);
            given(commandService.recordEscalationLevel(incident.getId(), TENANT_ID, 2)).willReturn(true);

            consumer.consumeIncidentEvent(record, acknowledgment);

            // Backlog #0-96: acknowledged after the transactional write returned.
            final InOrder order = inOrder(commandService, acknowledgment);
            order.verify(commandService).recordEscalationLevel(incident.getId(), TENANT_ID, 2);
            order.verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("ignores non-escalation event types on the same topic, acknowledges")
        void ignoresNonEscalationEventTypes() {
            final ConsumerRecord<String, String> record = new ConsumerRecord<>(
                    TOPIC, 0, 0L, "key", "{}");
            record.headers().add(new RecordHeader(IncidentEventTypes.HEADER_NAME,
                    IncidentEventTypes.INCIDENT_OPENED.getBytes(StandardCharsets.UTF_8)));

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(commandService).shouldHaveNoInteractions();
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("warns and still acknowledges when the incident isn't found locally")
        void warnsWhenIncidentNotFound() {
            final UUID incidentId = UUID.randomUUID();
            final ConsumerRecord<String, String> record =
                    buildEscalatedRecord(incidentId, 1);
            given(commandService.recordEscalationLevel(incidentId, TENANT_ID, 1)).willReturn(false);

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(acknowledgment).should().acknowledge();
        }
    }

    @Nested
    @DisplayName("poison pill handling")
    class PoisonPillHandling {

        @Test
        @DisplayName("dead-letters a record without X-Event-Type (backlog #0-96: it used to drop it)")
        void deadLettersWhenEventTypeHeaderMissing() {
            final ConsumerRecord<String, String> record = new ConsumerRecord<>(
                    TOPIC, 0, 0L, "key", "{}");

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(commandService).shouldHaveNoInteractions();
            then(deadLetterPublisher).should().deadLetterThenAcknowledge(eq(record), isNull(),
                    argThat(reason -> reason.contains(IncidentEventTypes.HEADER_NAME)), eq(acknowledgment));
            then(acknowledgment).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("a poison pill's reason names the exception and where, never the record's value "
                + "(backlog #0-96, found in review)")
        void reasonDoesNotQuoteTheRecord() {
            final String payload = "{\"incidentId\":\"top-secret-value\",\"tenantId\":\"" + TENANT_ID
                    + "\",\"escalationLevel\":1}";
            final ConsumerRecord<String, String> record = new ConsumerRecord<>(TOPIC, 0, 0L, "key", payload);
            record.headers().add(new RecordHeader(IncidentEventTypes.HEADER_NAME,
                    IncidentEventTypes.INCIDENT_ESCALATED.getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                    TENANT_ID.getBytes(StandardCharsets.UTF_8)));

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(deadLetterPublisher).should().deadLetterThenAcknowledge(eq(record), eq(TENANT_ID),
                    argThat(reason -> reason.startsWith("IllegalArgumentException at ")
                            && !reason.contains("top-secret-value")),
                    eq(acknowledgment));
        }

        @Test
        @DisplayName("dead-letters and acknowledges unparseable JSON (backlog #0-92: it used to drop it)")
        void deadLettersUnparseableJson() {
            final ConsumerRecord<String, String> record = new ConsumerRecord<>(
                    TOPIC, 0, 0L, "key", "not valid json !!!");
            record.headers().add(new RecordHeader(IncidentEventTypes.HEADER_NAME,
                    IncidentEventTypes.INCIDENT_ESCALATED.getBytes(StandardCharsets.UTF_8)));

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(commandService).shouldHaveNoInteractions();
            then(deadLetterPublisher).should().deadLetterThenAcknowledge(eq(record), isNull(),
                    anyString(), eq(acknowledgment));
            then(acknowledgment).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("dead-letters a record whose header names another tenant than its payload (backlog #0-92)")
        void deadLettersTenantMismatch() {
            final String payload = "{\"incidentId\":\"" + UUID.randomUUID()
                    + "\",\"tenantId\":\"" + TENANT_ID + "\",\"escalationLevel\":1}";
            final ConsumerRecord<String, String> record = new ConsumerRecord<>(TOPIC, 0, 0L, "key", payload);
            record.headers().add(new RecordHeader(IncidentEventTypes.HEADER_NAME,
                    IncidentEventTypes.INCIDENT_ESCALATED.getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                    "globex".getBytes(StandardCharsets.UTF_8)));

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(commandService).shouldHaveNoInteractions();
            then(deadLetterPublisher).should().deadLetterThenAcknowledge(eq(record), isNull(),
                    argThat(reason -> reason.contains("another tenant") && !reason.contains("globex")), eq(acknowledgment));
            then(acknowledgment).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("dead-letters a record without X-Tenant-Id, even with a valid payload tenant (backlog #0-92)")
        void deadLettersMissingTenantHeader() {
            final String payload = "{\"incidentId\":\"" + UUID.randomUUID()
                    + "\",\"tenantId\":\"" + TENANT_ID + "\",\"escalationLevel\":1}";
            final ConsumerRecord<String, String> record = new ConsumerRecord<>(TOPIC, 0, 0L, "key", payload);
            record.headers().add(new RecordHeader(IncidentEventTypes.HEADER_NAME,
                    IncidentEventTypes.INCIDENT_ESCALATED.getBytes(StandardCharsets.UTF_8)));

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(commandService).shouldHaveNoInteractions();
            then(deadLetterPublisher).should().deadLetterThenAcknowledge(eq(record), isNull(),
                    argThat(reason -> reason.contains("header is missing")), eq(acknowledgment));
            then(acknowledgment).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("dead-letters and acknowledges when the payload names no tenant")
        void deadLettersWhenTenantIdMissing() {
            final ConsumerRecord<String, String> record = new ConsumerRecord<>(
                    TOPIC, 0, 0L, "key",
                    "{\"incidentId\":\"" + UUID.randomUUID() + "\",\"escalationLevel\":1}");
            record.headers().add(new RecordHeader(IncidentEventTypes.HEADER_NAME,
                    IncidentEventTypes.INCIDENT_ESCALATED.getBytes(StandardCharsets.UTF_8)));

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(commandService).shouldHaveNoInteractions();
            then(deadLetterPublisher).should().deadLetterThenAcknowledge(eq(record), isNull(),
                    anyString(), eq(acknowledgment));
            then(acknowledgment).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("concurrency conflict and failures (backlog #40, #0-96)")
    class ConcurrencyConflictHandling {

        /**
         * Backlog #40's conflict is read again — since backlog #0-96 by
         * {@code nack}, as leaving the record unacknowledged skipped it.
         */
        @Test
        @DisplayName("an OptimisticLockingFailureException is nacked, never acknowledged")
        void redeliversOnOptimisticLockConflict() {
            final Incident incident = buildIncident();
            final ConsumerRecord<String, String> record =
                    buildEscalatedRecord(incident.getId(), 2);
            final OptimisticLockingFailureException conflict = new OptimisticLockingFailureException(
                    "Row was updated or deleted by another transaction");
            willThrow(conflict).given(commandService).recordEscalationLevel(incident.getId(), TENANT_ID, 2);

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(deadLetterPublisher).should().redeliverLater(record, TENANT_ID, acknowledgment, conflict);
            then(acknowledgment).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("any other failure is classified by DeadLetterPublisher (transient: nack, else DLT)")
        void otherFailureClassified() {
            final Incident incident = buildIncident();
            final ConsumerRecord<String, String> record =
                    buildEscalatedRecord(incident.getId(), 2);
            final RuntimeException failure = new DataAccessResourceFailureException("Database connection lost");
            willThrow(failure).given(commandService).recordEscalationLevel(incident.getId(), TENANT_ID, 2);

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(deadLetterPublisher).should()
                    .redeliverIfTransientElseDeadLetter(record, TENANT_ID, failure, acknowledgment);
            then(acknowledgment).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("the listener method is not @Transactional: the write commits before the acknowledgement")
        void listenerNotTransactional() throws Exception {
            assertThat(IncidentEscalationEventConsumer.class
                    .getMethod("consumeIncidentEvent", ConsumerRecord.class, Acknowledgment.class)
                    .isAnnotationPresent(Transactional.class)).isFalse();
            assertThat(IncidentEscalationEventConsumer.class.isAnnotationPresent(Transactional.class)).isFalse();
        }
    }
}
