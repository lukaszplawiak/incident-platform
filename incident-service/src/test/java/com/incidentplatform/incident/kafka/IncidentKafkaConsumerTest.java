package com.incidentplatform.incident.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.incidentplatform.incident.service.IncidentCommandService;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.ResolvedAlertNotification;
import com.incidentplatform.shared.events.SourceType;
import com.incidentplatform.shared.kafka.DeadLetterPublisher;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@DisplayName("IncidentKafkaConsumer")
class IncidentKafkaConsumerTest {

    @Mock
    private IncidentCommandService commandService;

    @Mock
    private Acknowledgment acknowledgment;

    @Mock
    private DeadLetterPublisher deadLetterPublisher;

    private IncidentKafkaConsumer consumer;
    private ObjectMapper objectMapper;

    private static final String TENANT_ID = "acme-corp";
    private static final String TOPIC_ALERTS_RAW = "alerts.raw";
    private static final String TOPIC_ALERTS_RESOLVED = "alerts.resolved";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        consumer = new IncidentKafkaConsumer(
                commandService, objectMapper, deadLetterPublisher);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private ConsumerRecord<String, String> buildRecord(String topic,
                                                       String payload,
                                                       String tenantId) {
        final ConsumerRecord<String, String> record =
                new ConsumerRecord<>(topic, 0, 0L, "key", payload);
        if (tenantId != null) {
            record.headers().add(new RecordHeader(
                    TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                    tenantId.getBytes(StandardCharsets.UTF_8)));
        }
        return record;
    }

    private String buildAlertJson() throws Exception {
        final UnifiedAlertDto alert = new UnifiedAlertDto(
                UUID.randomUUID(), TENANT_ID, "prometheus",
                SourceType.OPS, Severity.CRITICAL,
                "High CPU usage", "CPU exceeded 95%",
                Instant.now().minusSeconds(60),
                "prometheus:highcpu:server-1",
                Map.of("instance", "server-1:9100"),
                null
        );
        return objectMapper.writeValueAsString(alert);
    }

    private String buildResolvedJson() throws Exception {
        final ResolvedAlertNotification notification =
                ResolvedAlertNotification.of(
                        TENANT_ID, "prometheus",
                        "prometheus:highcpu:server-1",
                        Instant.now());
        return objectMapper.writeValueAsString(notification);
    }

    // ─── consumeAlert ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("consumeAlert")
    class ConsumeAlert {

        @Test
        @DisplayName("should call createFromAlert with correct tenantId from header")
        void shouldCallCreateFromAlertWithTenantId() throws Exception {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(TOPIC_ALERTS_RAW, buildAlertJson(), TENANT_ID);

            // when
            consumer.consumeAlert(record, acknowledgment);

            // then
            final ArgumentCaptor<String> tenantCaptor =
                    ArgumentCaptor.forClass(String.class);
            then(commandService).should()
                    .createFromAlert(any(), tenantCaptor.capture());
            assertThat(tenantCaptor.getValue()).isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("should acknowledge the record after successful processing")
        void shouldAcknowledgeAfterSuccess() throws Exception {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(TOPIC_ALERTS_RAW, buildAlertJson(), TENANT_ID);

            // when
            consumer.consumeAlert(record, acknowledgment);

            // then
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("should clear TenantContext after successful processing")
        void shouldClearTenantContextAfterProcessing() throws Exception {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(TOPIC_ALERTS_RAW, buildAlertJson(), TENANT_ID);

            // when
            consumer.consumeAlert(record, acknowledgment);

            // then
            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("should NOT acknowledge on transient error — Kafka will redeliver")
        void shouldNotAcknowledgeOnTransientError() throws Exception {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(TOPIC_ALERTS_RAW, buildAlertJson(), TENANT_ID);

            willThrow(new RuntimeException("DB connection lost"))
                    .given(commandService).createFromAlert(any(), any());

            // when — no exception propagated, consumer returns early
            consumer.consumeAlert(record, acknowledgment);

            // then — NOT acknowledged so Kafka redelivers after consumer restart
            then(acknowledgment).should(never()).acknowledge();
        }

        @Test
        @DisplayName("should clear TenantContext even on transient error")
        void shouldClearTenantContextOnTransientError() throws Exception {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(TOPIC_ALERTS_RAW, buildAlertJson(), TENANT_ID);

            willThrow(new RuntimeException("DB error"))
                    .given(commandService).createFromAlert(any(), any());

            // when
            consumer.consumeAlert(record, acknowledgment);

            // then — finally block must clear context even on transient error
            assertThat(TenantContext.getOrNull())
                    .as("TenantContext must be cleared even when processing fails")
                    .isNull();
        }

        @Test
        @DisplayName("should route unparseable JSON to DLT and acknowledge — poison pill")
        void shouldRoutePoisonPillToDltAndAcknowledge() {
            // given — completely invalid JSON: parseJson() wraps IOException as
            // IllegalArgumentException → caught by poison-pill catch → DLT
            final ConsumerRecord<String, String> record =
                    buildRecord(TOPIC_ALERTS_RAW, "{ invalid json }", TENANT_ID);

            // when
            consumer.consumeAlert(record, acknowledgment);

            // then — DLT receives the message
            then(deadLetterPublisher).should()
                    .publish(anyString(), anyString(), anyString(), anyString());

            // and — acknowledged to unblock partition
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("should NOT call commandService for poison pill")
        void shouldNotCallCommandServiceForPoisonPill() {
            // given — invalid JSON is caught before commandService is reached
            final ConsumerRecord<String, String> record =
                    buildRecord(TOPIC_ALERTS_RAW, "not-json-at-all", TENANT_ID);

            // when
            consumer.consumeAlert(record, acknowledgment);

            // then
            then(commandService).should(never()).createFromAlert(any(), any());
        }

        @Test
        @DisplayName("should fall back to payload tenantId when X-Tenant-Id header is missing")
        void shouldFallBackToPayloadTenantIdWhenHeaderMissing() throws Exception {
            // given — no header, but payload contains tenantId (step 2 of extraction)
            final ConsumerRecord<String, String> record =
                    buildRecord(TOPIC_ALERTS_RAW, buildAlertJson(), null);

            // when
            consumer.consumeAlert(record, acknowledgment);

            // then — tenantId resolved from payload, processing succeeds
            final ArgumentCaptor<String> tenantCaptor =
                    ArgumentCaptor.forClass(String.class);
            then(commandService).should()
                    .createFromAlert(any(), tenantCaptor.capture());
            assertThat(tenantCaptor.getValue()).isEqualTo(TENANT_ID);
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("should route to DLT when tenantId missing in both header and payload")
        void shouldRouteToDltWhenTenantIdMissingInBothHeaderAndPayload() {
            // given — no header AND payload has no tenantId → step 3: poison pill
            final String payloadWithoutTenant =
                    "{\"alertId\":\"" + UUID.randomUUID() + "\",\"severity\":\"CRITICAL\"}";

            final ConsumerRecord<String, String> record =
                    buildRecord(TOPIC_ALERTS_RAW, payloadWithoutTenant, null);

            // when
            consumer.consumeAlert(record, acknowledgment);

            // then — routed to DLT, partition unblocked
            then(deadLetterPublisher).should()
                    .publish(anyString(), anyString(), anyString(), anyString());
            then(acknowledgment).should().acknowledge();
            then(commandService).should(never()).createFromAlert(any(), any());
        }

        @Test
        @DisplayName("should set TenantContext from header before calling service")
        void shouldSetTenantContextFromHeaderBeforeCallingService() throws Exception {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(TOPIC_ALERTS_RAW, buildAlertJson(), "tenant-xyz");

            // when
            consumer.consumeAlert(record, acknowledgment);

            // then
            final ArgumentCaptor<String> tenantCaptor =
                    ArgumentCaptor.forClass(String.class);
            then(commandService).should()
                    .createFromAlert(any(), tenantCaptor.capture());
            assertThat(tenantCaptor.getValue()).isEqualTo("tenant-xyz");
        }

        @Test
        @DisplayName("should process different tenants correctly — no cross-tenant leak")
        void shouldProcessDifferentTenantsWithoutLeak() throws Exception {
            // given
            final ConsumerRecord<String, String> recordA =
                    buildRecord(TOPIC_ALERTS_RAW, buildAlertJson(), "tenant-a");
            final ConsumerRecord<String, String> recordB =
                    buildRecord(TOPIC_ALERTS_RAW, buildAlertJson(), "tenant-b");

            final ArgumentCaptor<String> tenantCaptor =
                    ArgumentCaptor.forClass(String.class);

            // when
            consumer.consumeAlert(recordA, acknowledgment);
            consumer.consumeAlert(recordB, acknowledgment);

            // then
            then(commandService).should(times(2))
                    .createFromAlert(any(), tenantCaptor.capture());

            assertThat(tenantCaptor.getAllValues())
                    .containsExactly("tenant-a", "tenant-b");

            assertThat(TenantContext.getOrNull())
                    .as("TenantContext must be empty after all records processed")
                    .isNull();
        }
    }

    // ─── consumeResolvedAlert ────────────────────────────────────────────────

    @Nested
    @DisplayName("consumeResolvedAlert")
    class ConsumeResolvedAlert {

        @Test
        @DisplayName("should call autoResolve with tenantId from header")
        void shouldCallAutoResolveWithTenantId() throws Exception {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(TOPIC_ALERTS_RESOLVED, buildResolvedJson(), TENANT_ID);

            // when
            consumer.consumeResolvedAlert(record, acknowledgment);

            // then
            final ArgumentCaptor<String> tenantCaptor =
                    ArgumentCaptor.forClass(String.class);
            then(commandService).should()
                    .autoResolve(any(), tenantCaptor.capture());
            assertThat(tenantCaptor.getValue()).isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("should acknowledge after successful processing")
        void shouldAcknowledgeAfterSuccess() throws Exception {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(TOPIC_ALERTS_RESOLVED, buildResolvedJson(), TENANT_ID);

            // when
            consumer.consumeResolvedAlert(record, acknowledgment);

            // then
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("should clear TenantContext after processing resolved alert")
        void shouldClearTenantContextAfterProcessing() throws Exception {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(TOPIC_ALERTS_RESOLVED, buildResolvedJson(), TENANT_ID);

            // when
            consumer.consumeResolvedAlert(record, acknowledgment);

            // then
            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("should NOT acknowledge on transient error")
        void shouldNotAcknowledgeOnTransientError() throws Exception {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(TOPIC_ALERTS_RESOLVED, buildResolvedJson(), TENANT_ID);

            willThrow(new RuntimeException("DB connection lost"))
                    .given(commandService).autoResolve(any(), any());

            // when
            consumer.consumeResolvedAlert(record, acknowledgment);

            // then
            then(acknowledgment).should(never()).acknowledge();
        }

        @Test
        @DisplayName("should route unparseable JSON to DLT and acknowledge — poison pill")
        void shouldRoutePoisonPillToDltAndAcknowledge() {
            // given — invalid JSON: parseJson() wraps IOException → poison pill
            final ConsumerRecord<String, String> record =
                    buildRecord(TOPIC_ALERTS_RESOLVED, "{ bad json }", TENANT_ID);

            // when
            consumer.consumeResolvedAlert(record, acknowledgment);

            // then
            then(deadLetterPublisher).should()
                    .publish(anyString(), anyString(), anyString(), anyString());
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("should fall back to payload tenantId when X-Tenant-Id header is missing")
        void shouldFallBackToPayloadTenantIdWhenHeaderMissing() throws Exception {
            // given — no header, but payload contains tenantId
            final ConsumerRecord<String, String> record =
                    buildRecord(TOPIC_ALERTS_RESOLVED, buildResolvedJson(), null);

            // when
            consumer.consumeResolvedAlert(record, acknowledgment);

            // then — tenantId resolved from payload, processing succeeds
            final ArgumentCaptor<String> tenantCaptor =
                    ArgumentCaptor.forClass(String.class);
            then(commandService).should()
                    .autoResolve(any(), tenantCaptor.capture());
            assertThat(tenantCaptor.getValue()).isEqualTo(TENANT_ID);
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("should route to DLT when tenantId missing in both header and payload")
        void shouldRouteToDltWhenTenantIdMissingInBothHeaderAndPayload() {
            // given — no header AND payload has no tenantId → poison pill
            final String payloadWithoutTenant =
                    "{\"alertFingerprint\":\"fp-1\",\"source\":\"prometheus\"}";

            final ConsumerRecord<String, String> record =
                    buildRecord(TOPIC_ALERTS_RESOLVED, payloadWithoutTenant, null);

            // when
            consumer.consumeResolvedAlert(record, acknowledgment);

            // then
            then(deadLetterPublisher).should()
                    .publish(anyString(), anyString(), anyString(), anyString());
            then(acknowledgment).should().acknowledge();
            then(commandService).should(never()).autoResolve(any(), any());
        }
    }
}