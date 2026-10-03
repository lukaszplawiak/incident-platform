package com.incidentplatform.postmortem.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.incidentplatform.postmortem.service.PostmortemPersistenceService;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.events.IncidentEventTypes;
import com.incidentplatform.shared.kafka.DeadLetterPublisher;
import com.incidentplatform.shared.kafka.TenantKafkaProducerInterceptor;
import com.incidentplatform.shared.kafka.TenantKafkaRecordResolver;
import com.incidentplatform.shared.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.CannotCreateTransactionException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("postmortem-service IncidentEventConsumer")
class IncidentEventConsumerTest {

    @Mock
    private PostmortemPersistenceService persistenceService;

    @Mock
    private Acknowledgment acknowledgment;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    /**
     * A real publisher (spied, to verify what it was handed) over a mocked
     * Kafka: since backlog #0-96 it decides whether a failed record is
     * acknowledged or nacked, so the test runs its real decision.
     */
    private DeadLetterPublisher deadLetterPublisher;

    private IncidentEventConsumer consumer;

    private static final String TENANT_ID = "acme-corp";
    private static final String TOPIC = "incidents.lifecycle";
    private static final UUID INCIDENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        deadLetterPublisher = spy(new DeadLetterPublisher(kafkaTemplate, new ObjectMapper(),
                "postmortem.dead-letter", "postmortem-service", new SimpleMeterRegistry()));
        lenient().when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(
                new SendResult<>(new ProducerRecord<>("postmortem.dead-letter", "v"),
                        new RecordMetadata(new TopicPartition("postmortem.dead-letter", 0), 0, 0, 0, 0, 0))));
        final ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        // Fixed (backlog #75): extractTenantId/parseJson moved to the
        // shared TenantKafkaRecordResolver — note the constructor's
        // argument order also changed to match production.
        consumer = new IncidentEventConsumer(
                persistenceService, deadLetterPublisher,
                new TenantKafkaRecordResolver(objectMapper, new SimpleMeterRegistry()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ConsumerRecord<String, String> buildRecord(String payload,
                                                       String tenantId,
                                                       String eventType) {
        final ConsumerRecord<String, String> record =
                new ConsumerRecord<>(TOPIC, 0, 0L, "key", payload);
        if (tenantId != null) {
            record.headers().add(new RecordHeader(
                    TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                    tenantId.getBytes(StandardCharsets.UTF_8)));
        }
        if (eventType != null) {
            record.headers().add(new RecordHeader(
                    IncidentEventTypes.HEADER_NAME,
                    eventType.getBytes(StandardCharsets.UTF_8)));
        }
        return record;
    }
    /** {@code payload} with its tenant replaced, so header and payload agree (backlog #0-92). */
    private static String forTenant(String payload, String tenant) {
        return payload.replace("\"" + TENANT_ID + "\"", "\"" + tenant + "\"");
    }


    private String resolvedEvent() {
        return String.format("""
                {
                  "incidentId": "%s",
                  "tenantId": "%s",
                  "title": "High CPU on prod-server-1",
                  "severity": "CRITICAL",
                  "resolvedBy": "%s",
                  "durationMinutes": 45,
                  "openedAt": "%s",
                  "occurredAt": "%s"
                }""",
                INCIDENT_ID, TENANT_ID, UUID.randomUUID(),
                Instant.now().minusSeconds(45 * 60L),
                Instant.now());
    }

    private String openedEvent() {
        return String.format("""
                {
                  "incidentId": "%s",
                  "tenantId": "%s",
                  "title": "High CPU",
                  "severity": "CRITICAL"
                }""", INCIDENT_ID, TENANT_ID);
    }

    private String acknowledgedEvent() {
        return String.format("""
                {
                  "incidentId": "%s",
                  "tenantId": "%s",
                  "acknowledgedBy": "%s"
                }""", INCIDENT_ID, TENANT_ID, UUID.randomUUID());
    }

    @Nested
    @DisplayName("IncidentResolvedEvent")
    class OnIncidentResolved {

        @Test
        @DisplayName("should write outbox entry with tenantId from the record (payload, matching header)")
        void shouldWriteOutboxEntryWithRecordTenant() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(resolvedEvent(), TENANT_ID,
                            IncidentEventTypes.INCIDENT_RESOLVED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then — consumer writes outbox entry, not calls Gemini
            final ArgumentCaptor<String> tenantCaptor =
                    ArgumentCaptor.forClass(String.class);
            then(persistenceService).should().createGeneratingRecord(
                    eq(INCIDENT_ID), tenantCaptor.capture(),
                    any(), any(), any(), any(), anyInt());
            assertThat(tenantCaptor.getValue()).isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("should pass correct incidentId to outbox write")
        void shouldPassCorrectIncidentId() {
            final ConsumerRecord<String, String> record =
                    buildRecord(resolvedEvent(), TENANT_ID,
                            IncidentEventTypes.INCIDENT_RESOLVED);

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(persistenceService).should().createGeneratingRecord(
                    eq(INCIDENT_ID), any(), any(), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("should pass correct severity to outbox write")
        void shouldPassCorrectSeverity() {
            final ConsumerRecord<String, String> record =
                    buildRecord(resolvedEvent(), TENANT_ID,
                            IncidentEventTypes.INCIDENT_RESOLVED);

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(persistenceService).should().createGeneratingRecord(
                    any(), any(), any(), eq(Severity.CRITICAL),
                    any(), any(), anyInt());
        }

        @Test
        @DisplayName("should pass correct durationMinutes to outbox write")
        void shouldPassCorrectDurationMinutes() {
            final ConsumerRecord<String, String> record =
                    buildRecord(resolvedEvent(), TENANT_ID,
                            IncidentEventTypes.INCIDENT_RESOLVED);

            consumer.consumeIncidentEvent(record, acknowledgment);

            final ArgumentCaptor<Integer> durationCaptor =
                    ArgumentCaptor.forClass(Integer.class);
            then(persistenceService).should().createGeneratingRecord(
                    any(), any(), any(), any(), any(), any(),
                    durationCaptor.capture());
            assertThat(durationCaptor.getValue()).isEqualTo(45);
        }

        @Test
        @DisplayName("should acknowledge after writing outbox entry")
        void shouldAcknowledgeAfterWritingOutboxEntry() {
            final ConsumerRecord<String, String> record =
                    buildRecord(resolvedEvent(), TENANT_ID,
                            IncidentEventTypes.INCIDENT_RESOLVED);

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(acknowledgment).should().acknowledge();
        }
    }

    @Nested
    @DisplayName("ignored events")
    class IgnoredEvents {

        @Test
        @DisplayName("should ignore IncidentOpenedEvent")
        void shouldIgnoreOpenedEvent() {
            final ConsumerRecord<String, String> record =
                    buildRecord(openedEvent(), TENANT_ID,
                            IncidentEventTypes.INCIDENT_OPENED);

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(persistenceService).should(never())
                    .createGeneratingRecord(any(), any(), any(),
                            any(), any(), any(), anyInt());
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("should ignore IncidentAcknowledgedEvent")
        void shouldIgnoreAcknowledgedEvent() {
            final ConsumerRecord<String, String> record =
                    buildRecord(acknowledgedEvent(), TENANT_ID,
                            IncidentEventTypes.INCIDENT_ACKNOWLEDGED);

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(persistenceService).should(never())
                    .createGeneratingRecord(any(), any(), any(),
                            any(), any(), any(), anyInt());
            then(acknowledgment).should().acknowledge();
        }
    }

    @Nested
    @DisplayName("missing event type header")
    class MissingEventTypeHeader {

        @Test
        @DisplayName("dead-letters a record without X-Event-Type, then acknowledges (backlog #0-96: it used to drop it)")
        void deadLettersWhenEventTypeHeaderMissing() {
            final ConsumerRecord<String, String> record =
                    buildRecord(resolvedEvent(), TENANT_ID, null);

            consumer.consumeIncidentEvent(record, acknowledgment);

            // its tenant is trustworthy (payload and header agree), so the copy names it
            then(deadLetterPublisher).should().deadLetterThenAcknowledge(eq(record), eq(TENANT_ID),
                    eq("missing X-Event-Type header"), eq(acknowledgment));
            then(acknowledgment).should().acknowledge();
            then(persistenceService).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("tenant context management")
    class TenantContextManagement {

        @Test
        @DisplayName("should clear TenantContext after processing")
        void shouldClearTenantContextAfterProcessing() {
            final ConsumerRecord<String, String> record =
                    buildRecord(resolvedEvent(), TENANT_ID,
                            IncidentEventTypes.INCIDENT_RESOLVED);

            consumer.consumeIncidentEvent(record, acknowledgment);

            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("a header naming another tenant than the payload: dead-lettered, processed under neither "
                + "(backlog #0-92: the header used to win)")
        void headerPayloadMismatchDeadLettered() {
            final String payloadWithDifferentTenant = String.format("""
                    {
                      "incidentId": "%s",
                      "tenantId": "payload-tenant",
                      "title": "High CPU",
                      "severity": "CRITICAL",
                      "resolvedBy": "%s",
                      "durationMinutes": 10,
                      "occurredAt": "%s"
                    }""", INCIDENT_ID, UUID.randomUUID(), Instant.now());

            final ConsumerRecord<String, String> record =
                    buildRecord(payloadWithDifferentTenant, "header-tenant",
                            IncidentEventTypes.INCIDENT_RESOLVED);

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(persistenceService).shouldHaveNoInteractions();
            then(deadLetterPublisher).should().deadLetterThenAcknowledge(any(), isNull(),
                    anyString(), eq(acknowledgment));
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("dead-letters a record without X-Tenant-Id, even with a valid payload tenant (backlog #0-92)")
        void shouldDeadLetterWhenTenantHeaderMissing() {
            // given — every platform sender writes the header (TenantRecords)
            final String payload = String.format("""
                    {
                      "incidentId": "%s",
                      "tenantId": "%s",
                      "title": "High CPU",
                      "severity": "CRITICAL",
                      "occurredAt": "%s"
                    }""", INCIDENT_ID, TENANT_ID, Instant.now());

            final ConsumerRecord<String, String> record =
                    buildRecord(payload, null, IncidentEventTypes.INCIDENT_OPENED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then — processed under no tenant
            then(persistenceService).shouldHaveNoInteractions();
            then(deadLetterPublisher).should().deadLetterThenAcknowledge(any(), isNull(),
                    anyString(), eq(acknowledgment));
            then(acknowledgment).should().acknowledge();
        }
    }

    @Nested
    @DisplayName("acknowledgment")
    class AcknowledgmentBehavior {

        @Test
        @DisplayName("a poison pill's reason names the exception and where, never the record's value "
                + "(backlog #0-96, found in review)")
        void reasonDoesNotQuoteTheRecord() {
            final ConsumerRecord<String, String> record = buildRecord(
                    "{\"incidentId\":\"top-secret-value\",\"tenantId\":\"" + TENANT_ID
                            + "\",\"severity\":\"CRITICAL\",\"title\":\"t\"}",
                    TENANT_ID, IncidentEventTypes.INCIDENT_RESOLVED);

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(deadLetterPublisher).should().deadLetterThenAcknowledge(eq(record), eq(TENANT_ID),
                    argThat(reason -> reason.startsWith("IllegalArgumentException at ")
                            && !reason.contains("top-secret-value")),
                    eq(acknowledgment));
        }


        @Test
        @DisplayName("nacks (never acknowledges) when the outbox write fails — genuinely transient DB error")
        void shouldNackWhenOutboxWriteFails() {
            // given — DB down during outbox INSERT; the consumer nacks the
            // record so it is read again after the DB recovers (backlog #0-96:
            // the rule is now KafkaFailures).
            // (Previously this tested PostmortemService throwing — after the
            // Outbox Pattern refactor the only operation that can fail here is
            // the createGeneratingRecord DB write.)
            //
            // Fixed (backlog #47): uses a real TransientDataAccessException
            // subtype now, not a plain RuntimeException — under the new,
            // inverted retry-classification model (see IncidentEventConsumer's
            // own Javadoc), only a genuinely recognized transient failure type
            // is treated as worth retrying; a plain RuntimeException would now
            // (correctly) be routed to DLT + acknowledged instead, which would
            // have made this specific test assertion false under the new model.
            final ConsumerRecord<String, String> record =
                    buildRecord(resolvedEvent(), TENANT_ID,
                            IncidentEventTypes.INCIDENT_RESOLVED);

            willThrow(new TransientDataAccessResourceException("db error"))
                    .given(persistenceService)
                    .createGeneratingRecord(any(), any(), any(),
                            any(), any(), any(), anyInt());

            consumer.consumeIncidentEvent(record, acknowledgment);

            // Backlog #0-96: nacked and read again; "not acknowledged" alone
            // let the next record's acknowledgement skip it.
            then(acknowledgment).should().nack(DeadLetterPublisher.REDELIVERY_DELAY);
            then(acknowledgment).should(never()).acknowledge();
            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("a database outage (non-transient in Spring's hierarchy) is nacked, not dead-lettered "
                + "(backlog #0-96)")
        void shouldNackOnDatabaseOutage() {
            final ConsumerRecord<String, String> record =
                    buildRecord(resolvedEvent(), TENANT_ID,
                            IncidentEventTypes.INCIDENT_RESOLVED);

            willThrow(new CannotCreateTransactionException("could not open JPA EntityManager"))
                    .given(persistenceService)
                    .createGeneratingRecord(any(), any(), any(),
                            any(), any(), any(), anyInt());

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(acknowledgment).should().nack(DeadLetterPublisher.REDELIVERY_DELAY);
            then(acknowledgment).should(never()).acknowledge();
            then(kafkaTemplate).shouldHaveNoInteractions();
        }

        /**
         * The actual regression test for backlog #47. Before this fix,
         * DateTimeParseException (thrown by Instant.parse on a malformed
         * timestamp) fell through uncaught into the old "assume transient,
         * retry forever" default — permanently blocking this partition,
         * since a malformed timestamp can never become parseable no matter
         * how many times the same message is redelivered.
         */
        @Test
        @DisplayName("should route to DLT and acknowledge on a malformed timestamp — " +
                "the actual backlog #47 regression test")
        void shouldAcknowledgeOnMalformedTimestamp() {
            final String malformedTimestampPayload = String.format("""
                    {
                      "incidentId": "%s",
                      "tenantId": "%s",
                      "title": "High CPU on prod-server-1",
                      "severity": "CRITICAL",
                      "durationMinutes": 45,
                      "openedAt": "not-a-valid-timestamp",
                      "occurredAt": "%s"
                    }""",
                    INCIDENT_ID, TENANT_ID, Instant.now());

            final ConsumerRecord<String, String> record =
                    buildRecord(malformedTimestampPayload, TENANT_ID,
                            IncidentEventTypes.INCIDENT_RESOLVED);

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(deadLetterPublisher).should().deadLetterThenAcknowledge(any(), eq(TENANT_ID),
                    any(), eq(acknowledgment));
            then(acknowledgment).should().acknowledge();
        }

        /**
         * Confirms the new default direction of the flipped model: an
         * exception that is genuinely unexpected (not a recognized
         * transient failure type, and not one of the specifically-named
         * poison-pill types) is now treated as non-retryable by default —
         * DLT + acknowledge — rather than the old "assume transient, retry
         * forever" default.
         */
        @Test
        @DisplayName("should route to DLT and acknowledge on an unrecognized, " +
                "non-transient exception — confirms the new default direction")
        void shouldAcknowledgeOnUnrecognizedNonTransientException() {
            // resolvedEvent() embeds Instant.now() and returns a fresh string
            // on every call — captured once here so the record built below
            // and the DLT verification afterward refer to the exact same
            // payload, not two different timestamps.
            final String payload = resolvedEvent();
            final ConsumerRecord<String, String> record =
                    buildRecord(payload, TENANT_ID,
                            IncidentEventTypes.INCIDENT_RESOLVED);

            willThrow(new IllegalStateException("unexpected programming error"))
                    .given(persistenceService)
                    .createGeneratingRecord(any(), any(), any(),
                            any(), any(), any(), anyInt());

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(deadLetterPublisher).should().deadLetterThenAcknowledge(any(), eq(TENANT_ID),
                    any(), eq(acknowledgment));
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("should route to DLT and acknowledge when severity is unrecognized — poison pill")
        void shouldAcknowledgeOnUnrecognizedSeverity() {
            final String badSeverityPayload = String.format("""
                    {
                      "incidentId": "%s",
                      "tenantId": "%s",
                      "title": "High CPU",
                      "severity": "UNKNOWN_SEVERITY",
                      "resolvedBy": "%s",
                      "durationMinutes": 10,
                      "occurredAt": "%s"
                    }""", INCIDENT_ID, TENANT_ID, UUID.randomUUID(), Instant.now());

            final ConsumerRecord<String, String> record =
                    buildRecord(badSeverityPayload, TENANT_ID,
                            IncidentEventTypes.INCIDENT_RESOLVED);

            consumer.consumeIncidentEvent(record, acknowledgment);

            // then — routed to DLT (previously: only logged and discarded),
            // acknowledged to skip the poison pill
            then(deadLetterPublisher).should().deadLetterThenAcknowledge(any(), eq(TENANT_ID),
                    anyString(), eq(acknowledgment));
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("should route to DLT and acknowledge when tenantId is missing from both header and payload — poison pill")
        void shouldRouteToDltWhenTenantIdMissing() {
            // given — no X-Tenant-Id header (buildRecord's tenantId param is
            // null) and no tenantId field in the payload either
            final String payloadWithoutTenantId = String.format("""
                    {
                      "incidentId": "%s",
                      "title": "High CPU",
                      "severity": "CRITICAL",
                      "occurredAt": "%s"
                    }""", INCIDENT_ID, Instant.now());

            final ConsumerRecord<String, String> record =
                    buildRecord(payloadWithoutTenantId, null,
                            IncidentEventTypes.INCIDENT_OPENED);

            consumer.consumeIncidentEvent(record, acknowledgment);

            // then — routed to DLT rather than silently discarded, with no
            // tenant since none was resolved (backlog #0-91: it used to be
            // the string "unknown", itself a valid tenant id)
            then(deadLetterPublisher).should().deadLetterThenAcknowledge(any(), isNull(),
                    anyString(), eq(acknowledgment));
            then(acknowledgment).should().acknowledge();
            then(persistenceService).shouldHaveNoInteractions();
        }
    }
}