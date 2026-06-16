package com.incidentplatform.ingestion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.ResolvedAlertNotification;
import com.incidentplatform.shared.events.SourceType;
import com.incidentplatform.shared.kafka.TenantKafkaProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("AlertKafkaProducer")
class AlertKafkaProducerTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private SendResult<String, String> sendResult;

    private AlertKafkaProducer producer;
    private ObjectMapper objectMapper;

    private static final String TENANT_ID = "acme-corp";
    private static final String ALERTS_RAW_TOPIC = "alerts.raw";
    private static final String ALERTS_RESOLVED_TOPIC = "alerts.resolved";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        producer = new AlertKafkaProducer(
                kafkaTemplate, objectMapper,
                ALERTS_RAW_TOPIC, ALERTS_RESOLVED_TOPIC);

        given(kafkaTemplate.send(any(ProducerRecord.class)))
                .willReturn(CompletableFuture.completedFuture(sendResult));
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private UnifiedAlertDto buildAlert() {
        return new UnifiedAlertDto(
                UUID.randomUUID(), TENANT_ID, "prometheus",
                SourceType.OPS, Severity.CRITICAL,
                "High CPU usage", "CPU exceeded 95%",
                Instant.now().minusSeconds(60),
                "prometheus:highcpu:server-1",
                Map.of("instance", "server-1:9100")
        );
    }

    private ResolvedAlertNotification buildResolved() {
        return ResolvedAlertNotification.of(
                TENANT_ID, "prometheus",
                "prometheus:highcpu:server-1",
                Instant.now());
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> captureRecord() {
        final ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        then(kafkaTemplate).should().send(captor.capture());
        return captor.getValue();
    }

    // ─── publishFiring ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("publishFiring")
    class PublishFiring {

        @Test
        @DisplayName("should set X-Tenant-Id header explicitly from alert.tenantId()")
        void shouldSetTenantIdHeaderFromDomainObject() {
            // given
            final UnifiedAlertDto alert = buildAlert();

            // when
            producer.publishFiring(alert);

            // then — X-Tenant-Id must be set from the domain object, NOT from
            // TenantContext (ThreadLocal). This makes the header thread-safe and
            // explicit — consistent with IncidentEventKafkaSender and
            // AuditEventKafkaSender.
            final ProducerRecord<String, String> record = captureRecord();
            final org.apache.kafka.common.header.Header header =
                    record.headers().lastHeader(
                            TenantKafkaProducerInterceptor.TENANT_ID_HEADER);

            assertThat(header).isNotNull();
            assertThat(new String(header.value(), StandardCharsets.UTF_8))
                    .isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("should send to alerts-raw topic")
        void shouldSendToAlertsRawTopic() {
            // given
            final UnifiedAlertDto alert = buildAlert();

            // when
            producer.publishFiring(alert);

            // then
            final ProducerRecord<String, String> record = captureRecord();
            assertThat(record.topic()).isEqualTo(ALERTS_RAW_TOPIC);
        }

        @Test
        @DisplayName("should use tenantId as partition key")
        void shouldUseTenantIdAsPartitionKey() {
            // given
            final UnifiedAlertDto alert = buildAlert();

            // when
            producer.publishFiring(alert);

            // then — tenantId as key ensures all alerts from the same tenant
            // are routed to the same partition, preserving ordering per tenant
            final ProducerRecord<String, String> record = captureRecord();
            assertThat(record.key()).isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("should serialize alert payload to JSON")
        void shouldSerializeAlertToJson() throws Exception {
            // given
            final UnifiedAlertDto alert = buildAlert();

            // when
            producer.publishFiring(alert);

            // then
            final ProducerRecord<String, String> record = captureRecord();
            final UnifiedAlertDto deserialized = objectMapper.readValue(
                    record.value(), UnifiedAlertDto.class);
            assertThat(deserialized.alertId()).isEqualTo(alert.alertId());
            assertThat(deserialized.tenantId()).isEqualTo(TENANT_ID);
            assertThat(deserialized.severity()).isEqualTo(Severity.CRITICAL);
        }

        @Test
        @DisplayName("should set correct header even without TenantContext — thread-safe")
        void shouldSetCorrectHeaderWithoutTenantContext() {
            // given — TenantContext is intentionally NOT set, simulating an
            // @Async or scheduled thread where the HTTP filter hasn't run.
            // The header must still be set correctly from the domain object.
            final UnifiedAlertDto alert = buildAlert();
            // TenantContext.clear() — already empty by default in test

            // when
            producer.publishFiring(alert);

            // then — header is present with correct value despite no TenantContext
            final ProducerRecord<String, String> record = captureRecord();
            final org.apache.kafka.common.header.Header header =
                    record.headers().lastHeader(
                            TenantKafkaProducerInterceptor.TENANT_ID_HEADER);

            assertThat(header).isNotNull();
            assertThat(new String(header.value(), StandardCharsets.UTF_8))
                    .isEqualTo(TENANT_ID);
        }
    }

    // ─── publishResolved ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("publishResolved")
    class PublishResolved {

        @Test
        @DisplayName("should set X-Tenant-Id header explicitly from notification.tenantId()")
        void shouldSetTenantIdHeaderFromDomainObject() {
            // given
            final ResolvedAlertNotification notification = buildResolved();

            // when
            producer.publishResolved(notification);

            // then
            final ProducerRecord<String, String> record = captureRecord();
            final org.apache.kafka.common.header.Header header =
                    record.headers().lastHeader(
                            TenantKafkaProducerInterceptor.TENANT_ID_HEADER);

            assertThat(header).isNotNull();
            assertThat(new String(header.value(), StandardCharsets.UTF_8))
                    .isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("should send to alerts-resolved topic")
        void shouldSendToAlertsResolvedTopic() {
            // given
            final ResolvedAlertNotification notification = buildResolved();

            // when
            producer.publishResolved(notification);

            // then
            final ProducerRecord<String, String> record = captureRecord();
            assertThat(record.topic()).isEqualTo(ALERTS_RESOLVED_TOPIC);
        }

        @Test
        @DisplayName("should use tenantId as partition key")
        void shouldUseTenantIdAsPartitionKey() {
            // given
            final ResolvedAlertNotification notification = buildResolved();

            // when
            producer.publishResolved(notification);

            // then
            final ProducerRecord<String, String> record = captureRecord();
            assertThat(record.key()).isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("should serialize notification payload to JSON")
        void shouldSerializeNotificationToJson() throws Exception {
            // given
            final ResolvedAlertNotification notification = buildResolved();

            // when
            producer.publishResolved(notification);

            // then
            final ProducerRecord<String, String> record = captureRecord();
            final ResolvedAlertNotification deserialized = objectMapper.readValue(
                    record.value(), ResolvedAlertNotification.class);
            assertThat(deserialized.tenantId()).isEqualTo(TENANT_ID);
            assertThat(deserialized.alertFingerprint())
                    .isEqualTo(notification.alertFingerprint());
        }
    }
}