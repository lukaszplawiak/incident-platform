package com.incidentplatform.postmortem.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.incidentplatform.postmortem.service.PostmortemPersistenceService;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.events.IncidentEventTypes;
import com.incidentplatform.shared.kafka.TenantKafkaProducerInterceptor;
import com.incidentplatform.shared.security.TenantContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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

    private IncidentEventConsumer consumer;

    private static final String TENANT_ID = "acme-corp";
    private static final String TOPIC = "incidents.lifecycle";
    private static final UUID INCIDENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        final ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        consumer = new IncidentEventConsumer(persistenceService, objectMapper);
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
        @DisplayName("should write outbox entry with tenantId from header")
        void shouldWriteOutboxEntryWithTenantIdFromHeader() {
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
        @DisplayName("should acknowledge and skip when X-Event-Type header is missing")
        void shouldAcknowledgeAndSkipWhenEventTypeHeaderMissing() {
            final ConsumerRecord<String, String> record =
                    buildRecord(resolvedEvent(), TENANT_ID, null);

            consumer.consumeIncidentEvent(record, acknowledgment);

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
        @DisplayName("header tenant wins over payload tenant")
        void headerTenantWinsOverPayloadTenant() {
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

            final ArgumentCaptor<String> tenantCaptor =
                    ArgumentCaptor.forClass(String.class);
            then(persistenceService).should().createGeneratingRecord(
                    any(), tenantCaptor.capture(), any(), any(), any(), any(), anyInt());
            assertThat(tenantCaptor.getValue()).isEqualTo("header-tenant");
        }
    }

    @Nested
    @DisplayName("acknowledgment")
    class AcknowledgmentBehavior {

        @Test
        @DisplayName("should NOT acknowledge when outbox write fails — DB unavailable")
        void shouldNotAcknowledgeWhenOutboxWriteFails() {
            // given — DB down during outbox INSERT; consumer must not acknowledge
            // so Kafka redelivers the event after the DB recovers.
            // (Previously this tested PostmortemService throwing — after the
            // Outbox Pattern refactor the only operation that can fail here is
            // the createGeneratingRecord DB write.)
            final ConsumerRecord<String, String> record =
                    buildRecord(resolvedEvent(), TENANT_ID,
                            IncidentEventTypes.INCIDENT_RESOLVED);

            willThrow(new RuntimeException("db error"))
                    .given(persistenceService)
                    .createGeneratingRecord(any(), any(), any(),
                            any(), any(), any(), anyInt());

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(acknowledgment).should(never()).acknowledge();
            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("should acknowledge when severity is unrecognized — poison pill")
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

            then(acknowledgment).should().acknowledge();
        }
    }
}