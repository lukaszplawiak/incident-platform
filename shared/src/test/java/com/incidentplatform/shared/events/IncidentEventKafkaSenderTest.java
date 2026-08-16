package com.incidentplatform.shared.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
                SourceType.OPS, Instant.now());
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

        @Test
        @DisplayName("sends with correct topic, key, and X-Event-Type header, " +
                "from a pre-serialized payload")
        void sendsWithCorrectRecordShape() throws Exception {
            given(kafkaTemplate.send(any(ProducerRecord.class)))
                    .willReturn(CompletableFuture.completedFuture(sendResult));

            final UUID incidentId = UUID.randomUUID();
            sender.sendRawSync(incidentId.toString(),
                    IncidentEventTypes.INCIDENT_RESOLVED, "{\"raw\":\"payload\"}",
                    Duration.ofSeconds(1));

            final ArgumentCaptor<ProducerRecord<String, String>> captor =
                    ArgumentCaptor.forClass(ProducerRecord.class);
            then(kafkaTemplate).should().send(captor.capture());

            final ProducerRecord<String, String> record = captor.getValue();
            assertThat(record.topic()).isEqualTo(TOPIC);
            assertThat(record.key()).isEqualTo(incidentId.toString());
            assertThat(record.value()).isEqualTo("{\"raw\":\"payload\"}");
            assertThat(new String(record.headers()
                    .lastHeader(IncidentEventTypes.HEADER_NAME).value()))
                    .isEqualTo(IncidentEventTypes.INCIDENT_RESOLVED);
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
                    UUID.randomUUID().toString(), IncidentEventTypes.INCIDENT_OPENED,
                    "{}", Duration.ofSeconds(1)))
                    .isInstanceOf(ExecutionException.class);
        }

        @Test
        @DisplayName("throws TimeoutException when the broker doesn't acknowledge in time")
        void throwsOnTimeout() {
            // A future that never completes — simulates a broker that never acks.
            given(kafkaTemplate.send(any(ProducerRecord.class)))
                    .willReturn(new CompletableFuture<>());

            assertThatThrownBy(() -> sender.sendRawSync(
                    UUID.randomUUID().toString(), IncidentEventTypes.INCIDENT_OPENED,
                    "{}", Duration.ofMillis(50)))
                    .isInstanceOf(TimeoutException.class);
        }

        @Test
        @DisplayName("does not throw when the send succeeds")
        void doesNotThrowOnSuccess() {
            given(kafkaTemplate.send(any(ProducerRecord.class)))
                    .willReturn(CompletableFuture.completedFuture(sendResult));

            assertThatCode(() -> sender.sendRawSync(
                    UUID.randomUUID().toString(), IncidentEventTypes.INCIDENT_OPENED,
                    "{}", Duration.ofSeconds(1)))
                    .doesNotThrowAnyException();
        }
    }
}