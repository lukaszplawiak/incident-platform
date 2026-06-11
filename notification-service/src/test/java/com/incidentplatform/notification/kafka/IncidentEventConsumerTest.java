package com.incidentplatform.notification.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.incidentplatform.notification.service.NotificationService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("notification-service IncidentEventConsumer")
class IncidentEventConsumerTest {

    @Mock
    private NotificationService notificationService;

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
        consumer = new IncidentEventConsumer(notificationService, objectMapper);
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
                  "title": "High CPU",
                  "severity": "CRITICAL",
                  "acknowledgedBy": "%s"
                }""", INCIDENT_ID, TENANT_ID, UUID.randomUUID());
    }

    private String resolvedEvent() {
        return String.format("""
                {
                  "incidentId": "%s",
                  "tenantId": "%s",
                  "title": "High CPU",
                  "severity": "HIGH",
                  "resolvedBy": "%s",
                  "durationMinutes": 30
                }""", INCIDENT_ID, TENANT_ID, UUID.randomUUID());
    }

    private String escalatedEvent() {
        return String.format("""
                {
                  "incidentId": "%s",
                  "tenantId": "%s",
                  "title": "High CPU",
                  "severity": "CRITICAL",
                  "escalationLevel": 1
                }""", INCIDENT_ID, TENANT_ID);
    }

    @Nested
    @DisplayName("tenant context management")
    class TenantContextManagement {

        @Test
        @DisplayName("should read tenantId from Kafka header not from event payload")
        void shouldReadTenantIdFromHeader() {
            // given
            final String payloadWithDifferentTenant = String.format("""
                    {
                      "incidentId": "%s",
                      "tenantId": "payload-tenant",
                      "title": "High CPU",
                      "severity": "CRITICAL"
                    }""", INCIDENT_ID);

            final ConsumerRecord<String, String> record =
                    buildRecord(payloadWithDifferentTenant, "header-tenant", IncidentEventTypes.INCIDENT_OPENED);

            final ArgumentCaptor<String> tenantCaptor =
                    ArgumentCaptor.forClass(String.class);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(notificationService).should().processEvent(
                    any(), any(), tenantCaptor.capture(), any(), any());
            assertThat(tenantCaptor.getValue()).isEqualTo("header-tenant");
        }

        @Test
        @DisplayName("should clear TenantContext after processing")
        void shouldClearTenantContextAfterProcessing() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(openedEvent(), TENANT_ID, IncidentEventTypes.INCIDENT_OPENED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("should clear TenantContext even when processing throws")
        void shouldClearTenantContextOnException() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(openedEvent(), TENANT_ID, IncidentEventTypes.INCIDENT_OPENED);

            org.mockito.BDDMockito.willThrow(new RuntimeException("service error"))
                    .given(notificationService)
                    .processEvent(any(), any(), any(), any(), any());

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
                    buildRecord(openedEvent(), "tenant-a", IncidentEventTypes.INCIDENT_OPENED);
            final ConsumerRecord<String, String> recordB =
                    buildRecord(openedEvent(), "tenant-b", IncidentEventTypes.INCIDENT_OPENED);

            final ArgumentCaptor<String> tenantCaptor =
                    ArgumentCaptor.forClass(String.class);

            // when
            consumer.consumeIncidentEvent(recordA, acknowledgment);
            consumer.consumeIncidentEvent(recordB, acknowledgment);

            // then
            then(notificationService).should(org.mockito.Mockito.times(2))
                    .processEvent(any(), any(), tenantCaptor.capture(), any(), any());

            assertThat(tenantCaptor.getAllValues())
                    .containsExactly("tenant-a", "tenant-b");
        }
    }

    @Nested
    @DisplayName("event type routing")
    class EventTypeRouting {

        @Test
        @DisplayName("should route IncidentOpenedEvent to notificationService")
        void shouldRouteOpenedEvent() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(openedEvent(), TENANT_ID, IncidentEventTypes.INCIDENT_OPENED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(notificationService).should().processEvent(
                    eq("IncidentOpenedEvent"),
                    eq(INCIDENT_ID),
                    eq(TENANT_ID),
                    eq(Severity.CRITICAL),
                    eq("High CPU")
            );
        }

        @Test
        @DisplayName("should route IncidentAcknowledgedEvent to notificationService")
        void shouldRouteAcknowledgedEvent() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(acknowledgedEvent(), TENANT_ID, IncidentEventTypes.INCIDENT_ACKNOWLEDGED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(notificationService).should().processEvent(
                    eq(IncidentEventTypes.INCIDENT_ACKNOWLEDGED), any(), any(), any(), any());
        }

        @Test
        @DisplayName("should route IncidentResolvedEvent to notificationService")
        void shouldRouteResolvedEvent() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(resolvedEvent(), TENANT_ID, IncidentEventTypes.INCIDENT_RESOLVED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(notificationService).should().processEvent(
                    eq(IncidentEventTypes.INCIDENT_RESOLVED), any(), any(), any(), any());
        }

        @Test
        @DisplayName("should acknowledge and skip when X-Event-Type header is missing")
        void shouldAcknowledgeAndSkipWhenEventTypeHeaderMissing() {
            // given — no eventType header
            final ConsumerRecord<String, String> record =
                    buildRecord(openedEvent(), TENANT_ID, null);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then — acknowledged to skip, no routing
            then(acknowledgment).should().acknowledge();
            then(notificationService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("should route IncidentEscalatedEvent to notificationService")
        void shouldRouteEscalatedEvent() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(escalatedEvent(), TENANT_ID, IncidentEventTypes.INCIDENT_ESCALATED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(notificationService).should().processEvent(
                    eq(IncidentEventTypes.INCIDENT_ESCALATED), any(), any(), any(), any());
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
                    buildRecord(openedEvent(), TENANT_ID, IncidentEventTypes.INCIDENT_OPENED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("should NOT acknowledge when notificationService throws transient error")
        void shouldNotAcknowledgeOnTransientException() {
            // given — RuntimeException is transient (Slack down, DB unavailable)
            // consumer should return without acknowledging so Kafka redelivers
            final ConsumerRecord<String, String> record =
                    buildRecord(openedEvent(), TENANT_ID,
                            IncidentEventTypes.INCIDENT_OPENED);

            org.mockito.BDDMockito.willThrow(new RuntimeException("Slack API down"))
                    .given(notificationService)
                    .processEvent(any(), any(), any(), any(), any());

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then — NOT acknowledged, Kafka will redeliver
            then(acknowledgment).should(org.mockito.Mockito.never()).acknowledge();
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
                      "severity": "UNKNOWN_SEVERITY"
                    }""", INCIDENT_ID, TENANT_ID);

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