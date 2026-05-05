package com.incidentplatform.incident.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.incidentplatform.incident.service.IncidentCommandService;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.ResolvedAlertNotification;
import com.incidentplatform.shared.events.SourceType;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("IncidentKafkaConsumer")
class IncidentKafkaConsumerTest {

    @Mock
    private IncidentCommandService commandService;

    @Mock
    private Acknowledgment acknowledgment;

    private IncidentKafkaConsumer consumer;
    private ObjectMapper objectMapper;

    private static final String TENANT_ID = "acme-corp";
    private static final String TOPIC_ALERTS_RAW = "alerts.raw";
    private static final String TOPIC_ALERTS_RESOLVED = "alerts.resolved";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        consumer = new IncidentKafkaConsumer(commandService, objectMapper);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

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
                java.util.Map.of("instance", "server-1:9100")
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
        @DisplayName("should clear TenantContext after processing")
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
        @DisplayName("should clear TenantContext even when processing throws")
        void shouldClearTenantContextOnException() throws Exception {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(TOPIC_ALERTS_RAW, buildAlertJson(), TENANT_ID);

            org.mockito.BDDMockito.willThrow(new RuntimeException("DB error"))
                    .given(commandService).createFromAlert(any(), any());

            // when / then
            assertThatThrownBy(() -> consumer.consumeAlert(record, acknowledgment))
                    .isInstanceOf(RuntimeException.class);

            assertThat(TenantContext.getOrNull())
                    .as("TenantContext must be cleared even when processing fails")
                    .isNull();
        }

        @Test
        @DisplayName("should throw when X-Tenant-Id header is missing")
        void shouldThrowWhenTenantHeaderMissing() throws Exception {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(TOPIC_ALERTS_RAW, buildAlertJson(), null);

            // when / then
            assertThatThrownBy(() -> consumer.consumeAlert(record, acknowledgment))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Missing tenantId header");

            then(commandService).should(never()).createFromAlert(any(), any());
        }

        @Test
        @DisplayName("should set TenantContext from header before calling service")
        void shouldSetTenantContextFromHeaderBeforeCallingService() throws Exception {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(TOPIC_ALERTS_RAW, buildAlertJson(), "tenant-xyz");

            // capture what TenantContext has when service is called
            final ArgumentCaptor<String> tenantCaptor =
                    ArgumentCaptor.forClass(String.class);

            // when
            consumer.consumeAlert(record, acknowledgment);

            // then
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
            then(commandService).should(org.mockito.Mockito.times(2))
                    .createFromAlert(any(), tenantCaptor.capture());

            assertThat(tenantCaptor.getAllValues())
                    .containsExactly("tenant-a", "tenant-b");

            assertThat(TenantContext.getOrNull())
                    .as("TenantContext must be empty after all records processed")
                    .isNull();
        }
    }

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
        @DisplayName("should throw when X-Tenant-Id header is missing")
        void shouldThrowWhenTenantHeaderMissing() throws Exception {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(TOPIC_ALERTS_RESOLVED, buildResolvedJson(), null);

            // when / then
            assertThatThrownBy(() ->
                    consumer.consumeResolvedAlert(record, acknowledgment))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Missing tenantId header");

            then(commandService).should(never()).autoResolve(any(), any());
        }
    }
}