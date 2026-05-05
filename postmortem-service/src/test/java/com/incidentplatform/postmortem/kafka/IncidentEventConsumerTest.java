package com.incidentplatform.postmortem.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.incidentplatform.postmortem.service.PostmortemService;
import com.incidentplatform.shared.domain.Severity;
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
@DisplayName("postmortem-service IncidentEventConsumer")
class IncidentEventConsumerTest {

    @Mock
    private PostmortemService postmortemService;

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
        consumer = new IncidentEventConsumer(postmortemService, objectMapper);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ConsumerRecord<String, String> buildRecord(String payload,
                                                       String tenantId) {
        final ConsumerRecord<String, String> record =
                new ConsumerRecord<>(TOPIC, 0, 0L, "key", payload);
        if (tenantId != null) {
            record.headers().add(new RecordHeader(
                    TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                    tenantId.getBytes(StandardCharsets.UTF_8)));
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
        @DisplayName("should trigger postmortem generation with tenantId from header")
        void shouldTriggerPostmortemGenerationWithTenantIdFromHeader() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(resolvedEvent(), TENANT_ID);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            final ArgumentCaptor<String> tenantCaptor =
                    ArgumentCaptor.forClass(String.class);
            then(postmortemService).should().generatePostmortem(
                    eq(INCIDENT_ID), tenantCaptor.capture(),
                    any(), any(), any(), any(), any());
            assertThat(tenantCaptor.getValue()).isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("should pass correct incidentId to postmortemService")
        void shouldPassCorrectIncidentId() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(resolvedEvent(), TENANT_ID);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(postmortemService).should().generatePostmortem(
                    eq(INCIDENT_ID), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("should pass correct severity to postmortemService")
        void shouldPassCorrectSeverity() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(resolvedEvent(), TENANT_ID);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(postmortemService).should().generatePostmortem(
                    any(), any(), any(), eq(Severity.CRITICAL),
                    any(), any(), any());
        }

        @Test
        @DisplayName("should pass correct durationMinutes to postmortemService")
        void shouldPassCorrectDurationMinutes() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(resolvedEvent(), TENANT_ID);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            final ArgumentCaptor<Integer> durationCaptor =
                    ArgumentCaptor.forClass(Integer.class);
            then(postmortemService).should().generatePostmortem(
                    any(), any(), any(), any(), any(), any(),
                    durationCaptor.capture());
            assertThat(durationCaptor.getValue()).isEqualTo(45);
        }

        @Test
        @DisplayName("should acknowledge after triggering postmortem")
        void shouldAcknowledgeAfterTriggeringPostmortem() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(resolvedEvent(), TENANT_ID);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(acknowledgment).should().acknowledge();
        }
    }

    @Nested
    @DisplayName("ignored events")
    class IgnoredEvents {

        @Test
        @DisplayName("should ignore IncidentOpenedEvent")
        void shouldIgnoreOpenedEvent() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(openedEvent(), TENANT_ID);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(postmortemService).should(never())
                    .generatePostmortem(any(), any(), any(),
                            any(), any(), any(), any());
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("should ignore IncidentAcknowledgedEvent")
        void shouldIgnoreAcknowledgedEvent() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(acknowledgedEvent(), TENANT_ID);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(postmortemService).should(never())
                    .generatePostmortem(any(), any(), any(),
                            any(), any(), any(), any());
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
                    buildRecord(resolvedEvent(), TENANT_ID);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("should clear TenantContext even when postmortemService throws")
        void shouldClearTenantContextOnException() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(resolvedEvent(), TENANT_ID);

            org.mockito.BDDMockito.willThrow(new RuntimeException("Gemini error"))
                    .given(postmortemService)
                    .generatePostmortem(any(), any(), any(),
                            any(), any(), any(), any());

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            assertThat(TenantContext.getOrNull()).isNull();
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("header tenant wins over payload tenant")
        void headerTenantWinsOverPayloadTenant() {
            // given
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
                    buildRecord(payloadWithDifferentTenant, "header-tenant");

            final ArgumentCaptor<String> tenantCaptor =
                    ArgumentCaptor.forClass(String.class);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(postmortemService).should().generatePostmortem(
                    any(), tenantCaptor.capture(), any(), any(), any(), any(), any());
            assertThat(tenantCaptor.getValue()).isEqualTo("header-tenant");
        }
    }
}