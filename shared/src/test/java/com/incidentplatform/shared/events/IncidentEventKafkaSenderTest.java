package com.incidentplatform.shared.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.incidentplatform.shared.kafka.TenantKafkaProducerInterceptor;
import com.incidentplatform.shared.security.InvalidTenantIdException;
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

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/**
 * Previously had no test file at all — neither the existing {@link
 * IncidentEventKafkaSender#send} nor the new (backlog #36) {@link
 * IncidentEventKafkaSender#sendRawSync} had any coverage.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IncidentEventKafkaSender")
class IncidentEventKafkaSenderTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private SendResult<String, String> sendResult;

    private IncidentEventKafkaSender sender;
    private ObjectMapper objectMapper;

    private static final String TOPIC = "incidents.lifecycle";
    private static final String TENANT_ID = "acme-corp";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        sender = new IncidentEventKafkaSender(kafkaTemplate, objectMapper, TOPIC);
    }

    private IncidentOpenedEvent buildEvent() {
        return new IncidentOpenedEvent(
                UUID.randomUUID(), TENANT_ID, UUID.randomUUID(),
                "prometheus:highcpu:server-1", "High CPU usage",
                com.incidentplatform.shared.domain.Severity.CRITICAL,
                SourceType.OPS, Instant.now(), UUID.randomUUID());
    }

    @Nested
    @DisplayName("send — async, fire-and-forget (existing behavior)")
    class Send {

        @Test
        @DisplayName("sends with correct topic, key, and X-Event-Type header")
        void sendsWithCorrectRecordShape() {
            given(kafkaTemplate.send(any(ProducerRecord.class)))
                    .willReturn(CompletableFuture.completedFuture(sendResult));

            final IncidentOpenedEvent event = buildEvent();
            sender.send(event, IncidentEventTypes.INCIDENT_OPENED);

            final ArgumentCaptor<ProducerRecord<String, String>> captor =
                    ArgumentCaptor.forClass(ProducerRecord.class);
            then(kafkaTemplate).should().send(captor.capture());

            final ProducerRecord<String, String> record = captor.getValue();
            assertThat(record.topic()).isEqualTo(TOPIC);
            assertThat(record.key()).isEqualTo(event.incidentId().toString());
            assertThat(record.headers().lastHeader(IncidentEventTypes.HEADER_NAME))
                    .isNotNull();
            assertThat(new String(record.headers()
                    .lastHeader(IncidentEventTypes.HEADER_NAME).value()))
                    .isEqualTo(IncidentEventTypes.INCIDENT_OPENED);
            // Backlog #0-91: the event's own tenant, not the thread's context.
            assertThat(new String(record.headers()
                    .lastHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER).value()))
                    .isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("does not throw when the send fails asynchronously")
        void doesNotThrowOnAsyncFailure() {
            given(kafkaTemplate.send(any(ProducerRecord.class)))
                    .willReturn(CompletableFuture.failedFuture(
                            new RuntimeException("Broker unreachable")));

            assertThatCode(() -> sender.send(buildEvent(),
                    IncidentEventTypes.INCIDENT_OPENED))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("sendRawSync — blocking, for IncidentEventOutboxScheduler (backlog #36)")
    class SendRawSync {

        /** An outbox payload names its own tenant, the row's (backlog #0-92). */
        private static final String PAYLOAD = "{\"tenantId\":\"" + TENANT_ID + "\",\"raw\":\"payload\"}";

        @Test
        @DisplayName("sends with correct topic, key, and X-Event-Type header, " +
                "from a pre-serialized payload")
        void sendsWithCorrectRecordShape() throws Exception {
            given(kafkaTemplate.send(any(ProducerRecord.class)))
                    .willReturn(CompletableFuture.completedFuture(sendResult));

            final UUID incidentId = UUID.randomUUID();
            sender.sendRawSync(incidentId.toString(), TENANT_ID,
                    IncidentEventTypes.INCIDENT_RESOLVED, PAYLOAD,
                    Duration.ofSeconds(1));

            final ArgumentCaptor<ProducerRecord<String, String>> captor =
                    ArgumentCaptor.forClass(ProducerRecord.class);
            then(kafkaTemplate).should().send(captor.capture());

            final ProducerRecord<String, String> record = captor.getValue();
            assertThat(record.topic()).isEqualTo(TOPIC);
            assertThat(record.key()).isEqualTo(incidentId.toString());
            assertThat(record.value()).isEqualTo(PAYLOAD);
            assertThat(new String(record.headers()
                    .lastHeader(IncidentEventTypes.HEADER_NAME).value()))
                    .isEqualTo(IncidentEventTypes.INCIDENT_RESOLVED);
            assertThat(new String(record.headers()
                    .lastHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER).value()))
                    .isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("refuses an invalid tenant before sending (backlog #0-91)")
        void refusesInvalidTenant() {
            assertThatThrownBy(() -> sender.sendRawSync(UUID.randomUUID().toString(), "Bad Tenant",
                    IncidentEventTypes.INCIDENT_OPENED, "{\"tenantId\":\"Bad Tenant\"}", Duration.ofSeconds(1)))
                    .isInstanceOf(InvalidTenantIdException.class);
            then(kafkaTemplate).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("refuses a row whose tenant is not its payload's, before sending; quotes neither "
                + "(found in review)")
        void refusesTenantOtherThanPayloads() {
            for (final String payload : new String[] {
                    "{\"tenantId\":\"globex\"}", "{\"raw\":\"payload\"}", "{\"tenantId\":42}"}) {
                assertThatThrownBy(() -> sender.sendRawSync(UUID.randomUUID().toString(), TENANT_ID,
                        IncidentEventTypes.INCIDENT_OPENED, payload, Duration.ofSeconds(1)))
                        .as(payload).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageNotContaining("globex").hasMessageNotContaining(TENANT_ID);
            }
            assertThatThrownBy(() -> sender.sendRawSync(UUID.randomUUID().toString(), TENANT_ID,
                    IncidentEventTypes.INCIDENT_OPENED, "not-json", Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class);
            then(kafkaTemplate).shouldHaveNoInteractions();
        }

        /**
         * The actual regression coverage for backlog #36's core
         * requirement: unlike {@link #sendsWithCorrectRecordShape}, this
         * verifies the method genuinely BLOCKS on and surfaces a real
         * send failure — IncidentEventOutboxScheduler depends on this to
         * correctly decide PUBLISHED vs. leave-PENDING.
         */
        @Test
        @DisplayName("throws ExecutionException when the send fails — " +
                "the caller can definitively detect failure, unlike send()")
        void throwsOnSendFailure() {
            given(kafkaTemplate.send(any(ProducerRecord.class)))
                    .willReturn(CompletableFuture.failedFuture(
                            new RuntimeException("Broker unreachable")));

            assertThatThrownBy(() -> sender.sendRawSync(
                    UUID.randomUUID().toString(), TENANT_ID, IncidentEventTypes.INCIDENT_OPENED,
                    PAYLOAD, Duration.ofSeconds(1)))
                    .isInstanceOf(ExecutionException.class);
        }

        @Test
        @DisplayName("throws TimeoutException when the broker doesn't acknowledge in time")
        void throwsOnTimeout() {
            // A future that never completes — simulates a broker that never acks.
            given(kafkaTemplate.send(any(ProducerRecord.class)))
                    .willReturn(new CompletableFuture<>());

            assertThatThrownBy(() -> sender.sendRawSync(
                    UUID.randomUUID().toString(), TENANT_ID, IncidentEventTypes.INCIDENT_OPENED,
                    PAYLOAD, Duration.ofMillis(50)))
                    .isInstanceOf(TimeoutException.class);
        }

        @Test
        @DisplayName("does not throw when the send succeeds")
        void doesNotThrowOnSuccess() {
            given(kafkaTemplate.send(any(ProducerRecord.class)))
                    .willReturn(CompletableFuture.completedFuture(sendResult));

            assertThatCode(() -> sender.sendRawSync(
                    UUID.randomUUID().toString(), TENANT_ID, IncidentEventTypes.INCIDENT_OPENED,
                    PAYLOAD, Duration.ofSeconds(1)))
                    .doesNotThrowAnyException();
        }
    }
}