package com.incidentplatform.shared.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.incidentplatform.shared.dto.AuditEventMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * {@link AuditEventKafkaSender#sendConfirmed} (backlog #0-88): it returns only
 * once Kafka acknowledged the event, and every other outcome is an
 * {@link AuditNotConfirmedException}, never a silent loss.
 */
@DisplayName("AuditEventKafkaSender.sendConfirmed")
class AuditEventKafkaSenderTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final AuditEventKafkaSender sender = new AuditEventKafkaSender(
            kafkaTemplate, new ObjectMapper().registerModule(new JavaTimeModule()), "audit.events");

    private final AuditEventMessage message = AuditEventMessage.auth(
            UUID.randomUUID(), "platform-operator", AuditEventTypes.MFA_RESET_BREAK_GLASS,
            "auth-service", "break-glass:Jane", "MFA reset", Map.of("reason", "lost phone"));

    @SuppressWarnings("unchecked")
    private void kafkaAnswers(CompletableFuture<SendResult<String, String>> future) {
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(future);
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> sentRecord() {
        final ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        org.mockito.Mockito.verify(kafkaTemplate).send(sent.capture());
        return sent.getValue();
    }

    private static String tenantHeader(ProducerRecord<String, String> record) {
        final Header header = record.headers().lastHeader("X-Tenant-Id");
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("returns once Kafka acknowledges; the record is keyed and headed by the tenant")
    void acknowledged() {
        kafkaAnswers(CompletableFuture.completedFuture(null));

        assertThatCode(() -> sender.sendConfirmed(message, Duration.ofSeconds(1))).doesNotThrowAnyException();
        final ProducerRecord<String, String> record = sentRecord();
        assertThat(record.topic()).isEqualTo("audit.events");
        assertThat(record.key()).isEqualTo("platform-operator");
        assertThat(tenantHeader(record)).isEqualTo("platform-operator");
    }

    @Test
    @DisplayName("the ordinary send carries the tenant header too, with no TenantContext (review of #0-88)")
    void ordinarySendHasTenantHeader() throws Exception {
        kafkaAnswers(CompletableFuture.completedFuture(null));

        sender.send(message);

        assertThat(tenantHeader(sentRecord())).isEqualTo("platform-operator");
    }

    @Test
    @DisplayName("an event without a tenant is sent without the header rather than failing")
    void noTenantNoHeader() throws Exception {
        kafkaAnswers(CompletableFuture.completedFuture(null));

        sender.send(AuditEventMessage.auth(UUID.randomUUID(), null, AuditEventTypes.USER_LOGIN_FAILED,
                "auth-service", "x", "login failed", Map.of()));

        assertThat(tenantHeader(sentRecord())).isNull();
    }

    @Test
    @DisplayName("a failed delivery is thrown, not swallowed")
    void failed() {
        kafkaAnswers(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThatThrownBy(() -> sender.sendConfirmed(message, Duration.ofSeconds(1)))
                .isInstanceOf(AuditNotConfirmedException.class)
                .hasMessageContaining("MFA_RESET_BREAK_GLASS")
                .hasRootCauseMessage("broker down");
    }

    @Test
    @DisplayName("no acknowledgement within the timeout is a failure")
    void timeout() {
        kafkaAnswers(new CompletableFuture<>());

        assertThatThrownBy(() -> sender.sendConfirmed(message, Duration.ofMillis(50)))
                .isInstanceOf(AuditNotConfirmedException.class)
                .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    @DisplayName("a send that throws before returning a future (no metadata) is a failure")
    void sendThrows() {
        given(kafkaTemplate.send(any(ProducerRecord.class)))
                .willThrow(new org.apache.kafka.common.errors.TimeoutException("no metadata"));

        assertThatThrownBy(() -> sender.sendConfirmed(message, Duration.ofSeconds(1)))
                .isInstanceOf(AuditNotConfirmedException.class);
    }

    @Test
    @DisplayName("an event that cannot be serialized is a failure, and nothing is sent (review of #0-88)")
    void serializationFails() throws Exception {
        final ObjectMapper failing = mock(ObjectMapper.class);
        given(failing.writeValueAsString(message))
                .willThrow(new com.fasterxml.jackson.core.JsonGenerationException("cannot serialize",
                        (com.fasterxml.jackson.core.JsonGenerator) null));
        final AuditEventKafkaSender failingSender = new AuditEventKafkaSender(kafkaTemplate, failing, "audit.events");

        assertThatThrownBy(() -> failingSender.sendConfirmed(message, Duration.ofSeconds(1)))
                .isInstanceOf(AuditNotConfirmedException.class)
                .hasCauseInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        org.mockito.Mockito.verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("an interrupt is a failure and keeps the thread's interrupt flag")
    void interrupted() {
        kafkaAnswers(new CompletableFuture<>());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> sender.sendConfirmed(message, Duration.ofSeconds(5)))
                    .isInstanceOf(AuditNotConfirmedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
