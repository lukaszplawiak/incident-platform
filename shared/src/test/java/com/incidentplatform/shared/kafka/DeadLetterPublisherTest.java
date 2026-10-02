package com.incidentplatform.shared.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

/** {@link DeadLetterPublisher#publishAndWait}: returns only once Kafka has the copy (backlog #0-84). */
@DisplayName("DeadLetterPublisher.publishAndWait")
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

    @Test
    @DisplayName("sends the original payload with its source and reason, keyed by service and tenant")
    void sendsAndWaits() throws Exception {
        given(kafkaTemplate.send(eq("incidents.dead-letter"), anyString(), anyString()))
                .willReturn(CompletableFuture.completedFuture(acknowledged()));
        final ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);

        publisher.publishAndWait("{bad}", "audit.events", "acme", "constraint: x", Duration.ofSeconds(1));

        then(kafkaTemplate).should().send(eq("incidents.dead-letter"), eq("incident-service:acme"), value.capture());
        final JsonNode record = objectMapper.readTree(value.getValue());
        assertThat(record.path("originalPayload").asText()).isEqualTo("{bad}");
        assertThat(record.path("sourceTopic").asText()).isEqualTo("audit.events");
        assertThat(record.path("tenantId").asText()).isEqualTo("acme");
        assertThat(record.path("errorReason").asText()).isEqualTo("constraint: x");
    }

    @Test
    @DisplayName("a failed or unacknowledged send is thrown, not only logged")
    void failureThrown() {
        given(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        assertThatThrownBy(() -> publisher.publishAndWait("{}", "audit.events", "acme", "r", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class).hasRootCauseMessage("broker down");

        given(kafkaTemplate.send(anyString(), anyString(), anyString())).willReturn(new CompletableFuture<>());
        assertThatThrownBy(() -> publisher.publishAndWait("{}", "audit.events", "acme", "r", Duration.ofMillis(20)))
                .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    @DisplayName("an interrupt is a failure and keeps the thread's interrupt flag")
    void interrupted() {
        given(kafkaTemplate.send(anyString(), anyString(), anyString())).willReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> publisher.publishAndWait("{}", "audit.events", "acme", "r",
                    Duration.ofSeconds(5))).isInstanceOf(IllegalStateException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
