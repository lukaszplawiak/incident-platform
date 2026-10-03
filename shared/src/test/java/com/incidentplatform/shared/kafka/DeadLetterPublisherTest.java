package com.incidentplatform.shared.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

/**
 * {@link DeadLetterPublisher}: {@code publishAndWait} returns only once Kafka
 * has the copy (backlog #0-84); a record carries its tenant only when the
 * tenant is valid, and its reason on one line (backlog #0-91/#0-92).
 */
@DisplayName("DeadLetterPublisher")
class DeadLetterPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DeadLetterPublisher publisher =
            new DeadLetterPublisher(kafkaTemplate, objectMapper, "incidents.dead-letter", "incident-service");

    private static SendResult<String, String> acknowledged() {
        return new SendResult<>(new ProducerRecord<>("incidents.dead-letter", "v"),
                new RecordMetadata(new TopicPartition("incidents.dead-letter", 0), 0, 0, 0, 0, 0));
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> sent() {
        final ArgumentCaptor<ProducerRecord<String, String>> record = ArgumentCaptor.forClass(ProducerRecord.class);
        then(kafkaTemplate).should().send(record.capture());
        return record.getValue();
    }

    private static String tenantHeader(ProducerRecord<String, String> record) {
        final Header header = record.headers().lastHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private void kafkaAcknowledges() {
        given(kafkaTemplate.send(any(ProducerRecord.class)))
                .willReturn(CompletableFuture.completedFuture(acknowledged()));
    }

    @Test
    @DisplayName("sends the original payload with its source and reason, keyed and headed by the tenant")
    void sendsAndWaits() throws Exception {
        kafkaAcknowledges();

        publisher.publishAndWait("{bad}", "audit.events", "acme", "constraint: x", Duration.ofSeconds(1));

        final ProducerRecord<String, String> record = sent();
        assertThat(record.topic()).isEqualTo("incidents.dead-letter");
        assertThat(record.key()).isEqualTo("incident-service:acme");
        assertThat(tenantHeader(record)).isEqualTo("acme");
        final JsonNode body = objectMapper.readTree(record.value());
        assertThat(body.path("originalPayload").asText()).isEqualTo("{bad}");
        assertThat(body.path("sourceTopic").asText()).isEqualTo("audit.events");
        assertThat(body.path("tenantId").asText()).isEqualTo("acme");
        assertThat(body.path("errorReason").asText()).isEqualTo("constraint: x");
    }

    @Test
    @DisplayName("no valid tenant: no header, a null tenantId and a key that names no tenant (backlog #0-91)")
    void noValidTenant() throws Exception {
        kafkaAcknowledges();

        publisher.publish("{bad}", "alerts.raw", "evil\nFAKE LOG LINE", "invalid tenant");

        final ProducerRecord<String, String> record = sent();
        assertThat(tenantHeader(record)).isNull();
        assertThat(record.headers().lastHeader(TenantRecords.TENANT_UNRESOLVED_HEADER)).isNotNull();
        assertThat(record.key()).isEqualTo("incident-service:" + DeadLetterPublisher.NO_TENANT);
        final JsonNode body = objectMapper.readTree(record.value());
        assertThat(body.get("tenantId").isNull()).isTrue();
        assertThat(record.value()).doesNotContain("FAKE LOG LINE");
    }

    @Test
    @DisplayName("the reason is kept on one line and cut, as it may quote the rejected record (backlog #0-92)")
    void reasonOnOneLine() throws Exception {
        kafkaAcknowledges();

        publisher.publish("{}", "alerts.raw", null, "Invalid UUID string: x\nFAKE " + "y".repeat(1_000));

        final JsonNode body = objectMapper.readTree(sent().value());
        assertThat(body.path("errorReason").asText()).doesNotContain("\n").hasSize(500);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("a failed or unacknowledged send is thrown, not only logged")
    void failureThrown() {
        given(kafkaTemplate.send(any(ProducerRecord.class)))
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        assertThatThrownBy(() -> publisher.publishAndWait("{}", "audit.events", "acme", "r", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class).hasRootCauseMessage("broker down");

        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(new CompletableFuture<>());
        assertThatThrownBy(() -> publisher.publishAndWait("{}", "audit.events", "acme", "r", Duration.ofMillis(20)))
                .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("an interrupt is a failure and keeps the thread's interrupt flag")
    void interrupted() {
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> publisher.publishAndWait("{}", "audit.events", "acme", "r",
                    Duration.ofSeconds(5))).isInstanceOf(IllegalStateException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("a dead-letter topic not named *.dead-letter fails construction, so the service's start "
            + "(found in review)")
    void topicMustBeDeadLetter() {
        assertThatThrownBy(() -> new DeadLetterPublisher(kafkaTemplate, objectMapper, "incidents.dlq", "incident-service"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".dead-letter");
        assertThatThrownBy(() -> new DeadLetterPublisher(kafkaTemplate, objectMapper, null, "incident-service"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
