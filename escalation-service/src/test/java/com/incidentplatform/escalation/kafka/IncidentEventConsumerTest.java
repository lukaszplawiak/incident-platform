package com.incidentplatform.escalation.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.incidentplatform.escalation.service.EscalationService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("escalation-service IncidentEventConsumer")
class IncidentEventConsumerTest {

    @Mock
    private EscalationService escalationService;

    @Mock
    private Acknowledgment acknowledgment;

    private IncidentEventConsumer consumer;
    private ObjectMapper objectMapper;

    private static final String TENANT_ID = "acme-corp";
    private static final String TOPIC = "incidents.lifecycle";
    private static final UUID INCIDENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        consumer = new IncidentEventConsumer(escalationService, objectMapper);
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
            // X-Event-Type header is set by IncidentEventKafkaSender on every
            // message. Tests add it explicitly since we're bypassing the
            // real producer.
            record.headers().add(new RecordHeader(
                    IncidentEventTypes.HEADER_NAME,
                    eventType.getBytes(StandardCharsets.UTF_8)));
        }
        return record;
    }

    private String openedEvent(Severity severity) {
        return String.format("""
                {
                  "incidentId": "%s",
                  "tenantId": "%s",
                  "title": "High CPU",
                  "severity": "%s",
                  "occurredAt": "%s"
                }""", INCIDENT_ID, TENANT_ID, severity.name(), Instant.now());
    }

    private String acknowledgedEvent() {
        return String.format("""
                {
                  "incidentId": "%s",
                  "tenantId": "%s",
                  "acknowledgedBy": "%s"
                }""", INCIDENT_ID, TENANT_ID, UUID.randomUUID());
    }

    private String resolvedEvent() {
        return String.format("""
                {
                  "incidentId": "%s",
                  "tenantId": "%s",
                  "resolvedBy": "%s",
                  "durationMinutes": 30
                }""", INCIDENT_ID, TENANT_ID, UUID.randomUUID());
    }

    @Nested
    @DisplayName("IncidentOpenedEvent")
    class OnIncidentOpened {

        @Test
        @DisplayName("should schedule escalation with tenantId from header")
        void shouldScheduleEscalationWithTenantId() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(openedEvent(Severity.CRITICAL), TENANT_ID,
                            IncidentEventTypes.INCIDENT_OPENED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            final ArgumentCaptor<String> tenantCaptor =
                    ArgumentCaptor.forClass(String.class);
            then(escalationService).should().scheduleEscalation(
                    eq(INCIDENT_ID), tenantCaptor.capture(),
                    any(), eq(Severity.CRITICAL), any());
            assertThat(tenantCaptor.getValue()).isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("should schedule escalation with correct severity")
        void shouldScheduleEscalationWithCorrectSeverity() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(openedEvent(Severity.HIGH), TENANT_ID,
                            IncidentEventTypes.INCIDENT_OPENED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(escalationService).should().scheduleEscalation(
                    any(), any(), any(), eq(Severity.HIGH), any());
        }

        @Test
        @DisplayName("should not cancel escalation on IncidentOpenedEvent")
        void shouldNotCancelEscalationOnOpenedEvent() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(openedEvent(Severity.CRITICAL), TENANT_ID,
                            IncidentEventTypes.INCIDENT_OPENED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(escalationService).should(never())
                    .cancelEscalation(any(), any());
        }
    }

    @Nested
    @DisplayName("IncidentAcknowledgedEvent")
    class OnIncidentAcknowledged {

        @Test
        @DisplayName("should cancel escalation with tenantId from header")
        void shouldCancelEscalationWithTenantId() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(acknowledgedEvent(), TENANT_ID,
                            IncidentEventTypes.INCIDENT_ACKNOWLEDGED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(escalationService).should()
                    .cancelEscalation(eq(INCIDENT_ID), eq(TENANT_ID));
        }

        @Test
        @DisplayName("should not schedule escalation on IncidentAcknowledgedEvent")
        void shouldNotScheduleEscalationOnAcknowledgedEvent() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(acknowledgedEvent(), TENANT_ID,
                            IncidentEventTypes.INCIDENT_ACKNOWLEDGED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(escalationService).should(never())
                    .scheduleEscalation(any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("ignored events")
    class IgnoredEvents {

        @Test
        @DisplayName("should ignore IncidentResolvedEvent")
        void shouldIgnoreResolvedEvent() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(resolvedEvent(), TENANT_ID,
                            IncidentEventTypes.INCIDENT_RESOLVED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(escalationService).should(never())
                    .scheduleEscalation(any(), any(), any(), any(), any());
            then(escalationService).should(never())
                    .cancelEscalation(any(), any());
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("should ignore IncidentEscalatedEvent (own published event)")
        void shouldIgnoreEscalatedEvent() {
            // given — escalation-service also consumes incidents-lifecycle,
            // so it receives the IncidentEscalatedEvent it just published.
            final String escalatedEvent = String.format("""
                    {
                      "incidentId": "%s",
                      "tenantId": "%s",
                      "escalationLevel": 1,
                      "severity": "CRITICAL",
                      "title": "High CPU"
                    }""", INCIDENT_ID, TENANT_ID);

            final ConsumerRecord<String, String> record =
                    buildRecord(escalatedEvent, TENANT_ID,
                            IncidentEventTypes.INCIDENT_ESCALATED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(escalationService).should(never())
                    .scheduleEscalation(any(), any(), any(), any(), any());
            then(escalationService).should(never())
                    .cancelEscalation(any(), any());
            then(acknowledgment).should().acknowledge();
        }
    }

    @Nested
    @DisplayName("missing event type header")
    class MissingEventTypeHeader {

        @Test
        @DisplayName("should acknowledge and skip when X-Event-Type header is missing")
        void shouldAcknowledgeAndSkipWhenEventTypeHeaderMissing() {
            // given — no eventType header (e.g. a producer that forgot to set it)
            final ConsumerRecord<String, String> record =
                    buildRecord(openedEvent(Severity.CRITICAL), TENANT_ID, null);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then — acknowledged to skip, no routing
            then(acknowledgment).should().acknowledge();
            then(escalationService).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("tenant context management")
    class TenantContextManagement {

        @Test
        @DisplayName("should clear TenantContext after processing")
        void shouldClearTenantContextAfterProcessing() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(openedEvent(Severity.CRITICAL), TENANT_ID,
                            IncidentEventTypes.INCIDENT_OPENED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("should not leak tenantId between sequential records")
        void shouldNotLeakTenantIdBetweenRecords() {
            // given
            final ConsumerRecord<String, String> recordA =
                    buildRecord(openedEvent(Severity.CRITICAL), "tenant-a",
                            IncidentEventTypes.INCIDENT_OPENED);
            final ConsumerRecord<String, String> recordB =
                    buildRecord(acknowledgedEvent(), "tenant-b",
                            IncidentEventTypes.INCIDENT_ACKNOWLEDGED);

            final ArgumentCaptor<String> scheduleCaptor =
                    ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<String> cancelCaptor =
                    ArgumentCaptor.forClass(String.class);

            // when
            consumer.consumeIncidentEvent(recordA, acknowledgment);
            consumer.consumeIncidentEvent(recordB, acknowledgment);

            // then
            then(escalationService).should()
                    .scheduleEscalation(any(), scheduleCaptor.capture(),
                            any(), any(), any());
            then(escalationService).should()
                    .cancelEscalation(any(), cancelCaptor.capture());

            assertThat(scheduleCaptor.getValue()).isEqualTo("tenant-a");
            assertThat(cancelCaptor.getValue()).isEqualTo("tenant-b");
        }
    }

    @Nested
    @DisplayName("acknowledgment")
    class AcknowledgmentBehavior {

        @Test
        @DisplayName("should acknowledge after successful processing")
        void shouldAcknowledgeAfterSuccess() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(openedEvent(Severity.CRITICAL), TENANT_ID,
                            IncidentEventTypes.INCIDENT_OPENED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("should NOT acknowledge when escalationService throws transient error")
        void shouldNotAcknowledgeOnTransientException() {
            // given — RuntimeException is transient (DB down, network issue)
            // consumer should return without acknowledging so Kafka redelivers
            final ConsumerRecord<String, String> record =
                    buildRecord(openedEvent(Severity.CRITICAL), TENANT_ID,
                            IncidentEventTypes.INCIDENT_OPENED);

            org.mockito.BDDMockito.willThrow(new RuntimeException("db error"))
                    .given(escalationService)
                    .scheduleEscalation(any(), any(), any(), any(), any());

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then — NOT acknowledged, Kafka will redeliver
            then(acknowledgment).should(never()).acknowledge();
            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("should acknowledge when severity is unrecognized — poison pill")
        void shouldAcknowledgeOnUnrecognizedSeverity() {
            // given — bad severity cannot be fixed by retrying
            final String badSeverityPayload = String.format("""
                    {
                      "incidentId": "%s",
                      "tenantId": "%s",
                      "title": "High CPU",
                      "severity": "UNKNOWN_SEVERITY",
                      "occurredAt": "%s"
                    }""", INCIDENT_ID, TENANT_ID, Instant.now());

            final ConsumerRecord<String, String> record =
                    buildRecord(badSeverityPayload, TENANT_ID,
                            IncidentEventTypes.INCIDENT_OPENED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then — acknowledged to skip the poison pill
            then(acknowledgment).should().acknowledge();
        }
    }
}