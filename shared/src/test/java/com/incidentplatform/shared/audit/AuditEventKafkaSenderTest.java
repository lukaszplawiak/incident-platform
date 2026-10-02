package com.incidentplatform.shared.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.incidentplatform.shared.dto.AuditEventMessage;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
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
 * {@link AuditEventKafkaSender}: {@code sendForRelay} for the outbox relay
 * (backlog #0-84) hands back Kafka's acknowledgement to wait for; every
 * record carries its tenant as key and {@code X-Tenant-Id} header (#0-88).
 */
@DisplayName("AuditEventKafkaSender")
class AuditEventKafkaSenderTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final AuditEventKafkaSender sender = new AuditEventKafkaSender(
            kafkaTemplate, new ObjectMapper().registerModule(new JavaTimeModule()), "audit.events");

    private final AuditEventMessage message = AuditEventMessage.auth(
            UUID.randomUUID(), "acme", AuditEventTypes.USER_LOGIN,
            "auth-service", "user-1", "Login", Map.of());

    @SuppressWarnings("unchecked")
    private void kafkaAnswers(CompletableFuture<SendResult<String, String>> future) {
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(future);
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> sentRecord() {
        final ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        Mockito.verify(kafkaTemplate).send(sent.capture());
        return sent.getValue();
    }

    private static String tenantHeader(ProducerRecord<String, String> record) {
        final Header header = record.headers().lastHeader("X-Tenant-Id");
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("sendForRelay returns Kafka's acknowledgement; the record is keyed and headed by the tenant")
    void sendForRelay() {
        final CompletableFuture<SendResult<String, String>> ack = new CompletableFuture<>();
        kafkaAnswers(ack);

        assertThat(sender.sendForRelay("acme", "{\"x\":1}")).isSameAs(ack);

        final ProducerRecord<String, String> record = sentRecord();
        assertThat(record.topic()).isEqualTo("audit.events");
        assertThat(record.key()).isEqualTo("acme");
        assertThat(record.value()).isEqualTo("{\"x\":1}");
        assertThat(tenantHeader(record)).isEqualTo("acme");
    }

    @Test
    @DisplayName("the direct send (services without an outbox) carries the tenant header too")
    void directSendHasTenantHeader() throws Exception {
        kafkaAnswers(CompletableFuture.completedFuture(null));

        sender.send(message);

        assertThat(tenantHeader(sentRecord())).isEqualTo("acme");
    }

    @Test
    @DisplayName("an event without a tenant is sent without the header rather than failing")
    void noTenantNoHeader() throws Exception {
        kafkaAnswers(CompletableFuture.completedFuture(null));

        sender.send(AuditEventMessage.auth(UUID.randomUUID(), null, AuditEventTypes.USER_LOGIN,
                "auth-service", "user-1", "Login", Map.of()));

        assertThat(tenantHeader(sentRecord())).isNull();
    }

    @Test
    @DisplayName("serialize writes the event, its eventId included, as JSON the consumer reads back")
    void serializeKeepsEventId() throws Exception {
        final String json = sender.serialize(message);

        final AuditEventMessage read = new ObjectMapper().registerModule(new JavaTimeModule())
                .readValue(json, AuditEventMessage.class);
        assertThat(read.eventId()).isEqualTo(message.eventId()).isNotNull();
        assertThat(read.eventType()).isEqualTo(AuditEventTypes.USER_LOGIN);
    }
}
