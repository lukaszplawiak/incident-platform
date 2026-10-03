package com.incidentplatform.shared.kafka;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.CannotCreateTransactionException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * {@link DeadLetterPublisher}: a copy is sent and waited for (backlog #0-84);
 * a record carries its tenant only when the tenant is valid, and its reason on
 * one line (backlog #0-91/#0-92); a consumed record is acknowledged only once
 * its copy is stored, nacked otherwise, and given up on after its redelivery
 * deadline; copies are bounded in time and size, and an unexpected exception
 * is recorded by its type (backlog #0-96).
 */
@DisplayName("DeadLetterPublisher")
class DeadLetterPublisherTest {

    private static final Duration DEADLINE = Duration.ofMinutes(30);

    /** A clock the test moves. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-10-03T10:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MovableClock clock = new MovableClock();
    private final DeadLetterPublisher publisher = new DeadLetterPublisher(
            kafkaTemplate, objectMapper, "incidents.dead-letter", "incident-service", meterRegistry, DEADLINE,
            clock);
    private ListAppender<ILoggingEvent> logs;

    @AfterEach
    void releaseLogs() {
        if (logs != null) {
            ((Logger) LoggerFactory.getLogger(DeadLetterPublisher.class)).detachAppender(logs);
            logs.stop();
        }
    }

    private ListAppender<ILoggingEvent> captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(DeadLetterPublisher.class)).addAppender(logs);
        return logs;
    }

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

    @SuppressWarnings("unchecked")
    private void kafkaFails() {
        given(kafkaTemplate.send(any(ProducerRecord.class)))
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
    }

    private static ConsumerRecord<String, String> consumed() {
        return new ConsumerRecord<>("incidents.lifecycle", 2, 41L, "k", "{poison}");
    }

    private double redeliveries(String reason) {
        return meterRegistry.get(DeadLetterPublisher.REDELIVERY_COUNTER).tag("reason", reason).counter().count();
    }

    private double gaveUp() {
        return meterRegistry.get(DeadLetterPublisher.GAVE_UP_COUNTER).counter().count();
    }

    // ── the copy ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sends the original payload with its source and reason, keyed and headed by the tenant")
    void sendsAndWaits() throws Exception {
        kafkaAcknowledges();

        publisher.publishAndWait("{bad}", "audit.events", "acme", "constraint: x");

        final ProducerRecord<String, String> record = sent();
        assertThat(record.topic()).isEqualTo("incidents.dead-letter");
        assertThat(record.key()).isEqualTo("incident-service:acme");
        assertThat(tenantHeader(record)).isEqualTo("acme");
        final JsonNode body = objectMapper.readTree(record.value());
        assertThat(body.path("originalPayload").asText()).isEqualTo("{bad}");
        assertThat(body.path("sourceTopic").asText()).isEqualTo("audit.events");
        assertThat(body.path("tenantId").asText()).isEqualTo("acme");
        assertThat(body.path("errorReason").asText()).isEqualTo("constraint: x");
        assertThat(body.has("originalPayloadTruncated")).isFalse();
    }

    @Test
    @DisplayName("no valid tenant: no header, a null tenantId and a key that names no tenant (backlog #0-91)")
    void noValidTenant() throws Exception {
        kafkaAcknowledges();

        publisher.publishAndWait("{bad}", "alerts.raw", "evil\nFAKE LOG LINE", "invalid tenant");

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

        publisher.publishAndWait("{}", "alerts.raw", null, "Invalid UUID string: x\nFAKE " + "y".repeat(1_000));

        final JsonNode body = objectMapper.readTree(sent().value());
        assertThat(body.path("errorReason").asText()).doesNotContain("\n").hasSize(500);
    }

    @Test
    @DisplayName("a payload that is not a string is sent as its JSON")
    void objectPayloadSerialized() throws Exception {
        kafkaAcknowledges();

        publisher.publishAndWait(objectMapper.readTree("{\"a\":1}"), "prometheus", "acme", "r");

        final JsonNode body = objectMapper.readTree(sent().value());
        assertThat(body.path("originalPayload").asText()).isEqualTo("{\"a\":1}");
        assertThat(body.has("sourcePartition")).isFalse();
    }

    @Test
    @DisplayName("a payload over the limit is cut, marked and sized, so the copy always fits in a record "
            + "(backlog #0-96, found in review)")
    void oversizedPayloadTruncated() throws Exception {
        kafkaAcknowledges();
        final String huge = "\"é\"".repeat(DeadLetterPublisher.MAX_ORIGINAL_PAYLOAD_BYTES);

        publisher.publishAndWait(huge, "alerts.raw", "acme", "too big");

        final JsonNode body = objectMapper.readTree(sent().value());
        assertThat(body.path("originalPayloadTruncated").asBoolean()).isTrue();
        assertThat(body.path("originalPayloadBytes").asInt()).isEqualTo(huge.getBytes(StandardCharsets.UTF_8).length);
        final String kept = body.path("originalPayload").asText();
        assertThat(kept.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(DeadLetterPublisher.MAX_ORIGINAL_PAYLOAD_BYTES);
        assertThat(huge).startsWith(kept);
    }

    @Test
    @DisplayName("a UTF-8 cut never splits a character")
    void truncateUtf8() {
        final byte[] bytes = "aé€😀".getBytes(StandardCharsets.UTF_8); // 1 + 2 + 3 + 4 bytes
        assertThat(DeadLetterPublisher.truncateUtf8(bytes, 1)).isEqualTo("a");
        assertThat(DeadLetterPublisher.truncateUtf8(bytes, 2)).isEqualTo("a");
        assertThat(DeadLetterPublisher.truncateUtf8(bytes, 3)).isEqualTo("aé");
        assertThat(DeadLetterPublisher.truncateUtf8(bytes, 9)).isEqualTo("aé€");
        assertThat(DeadLetterPublisher.truncateUtf8(bytes, 10)).isEqualTo("aé€😀");
        assertThat(DeadLetterPublisher.truncateUtf8(bytes, 100)).isEqualTo("aé€😀");
    }

    // ── waiting ─────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("a failed send, a send that throws at once and a copy never acknowledged are thrown")
    void failuresThrown() {
        kafkaFails();
        assertThatThrownBy(() -> publisher.publishAndWait("{}", "audit.events", "acme", "r"))
                .isInstanceOf(DeadLetterNotStoredException.class).hasRootCauseMessage("broker down");

        given(kafkaTemplate.send(any(ProducerRecord.class))).willThrow(new KafkaException("no metadata"));
        assertThatThrownBy(() -> publisher.publishAndWait("{}", "alerts", "acme", "r"))
                .isInstanceOf(DeadLetterNotStoredException.class).hasRootCauseMessage("no metadata");

        // The deadline is already past (the clock does not move): the wait is cut short.
        final CompletableFuture<Void> never = new CompletableFuture<>();
        assertThatThrownBy(() -> publisher.await(List.of(never),
                clock.instant().minus(DeadLetterPublisher.DEAD_LETTER_TIMEOUT)))
                .isInstanceOf(DeadLetterNotStoredException.class).hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    @DisplayName("copies started together are awaited under one deadline, the first failure thrown")
    void awaitTogether() {
        final CompletableFuture<Void> stored = CompletableFuture.completedFuture(null);
        final CompletableFuture<Void> failed = CompletableFuture.failedFuture(
                new DeadLetterNotStoredException("not stored", new RuntimeException()));

        assertThatCode(() -> publisher.await(List.of(stored, stored), clock.instant())).doesNotThrowAnyException();
        assertThatThrownBy(() -> publisher.await(List.of(stored, failed), clock.instant()))
                .isInstanceOf(DeadLetterNotStoredException.class).hasMessage("not stored");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("publishAsync never throws: a send failing at once is a failed copy")
    void publishAsyncNeverThrows() {
        given(kafkaTemplate.send(any(ProducerRecord.class))).willThrow(new KafkaException("no metadata"));

        final CompletableFuture<Void> copy = publisher.publishAsync("{}", "alerts", "acme", "r");

        assertThat(copy).isCompletedExceptionally();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("an interrupt is a failure and keeps the thread's interrupt flag")
    void interrupted() {
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> publisher.publishAndWait("{}", "audit.events", "acme", "r"))
                    .isInstanceOf(DeadLetterNotStoredException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    // ── consumed records ────────────────────────────────────────────────────

    @Test
    @DisplayName("a consumed record is acknowledged only after Kafka took its copy, which names its source "
            + "partition and offset (backlog #0-96)")
    void deadLetterThenAcknowledge() throws Exception {
        kafkaAcknowledges();
        final Acknowledgment ack = mock(Acknowledgment.class);

        assertThat(publisher.deadLetterThenAcknowledge(consumed(), "acme", "bad", ack)).isTrue();

        final InOrder order = inOrder(kafkaTemplate, ack);
        order.verify(kafkaTemplate).send(any(ProducerRecord.class));
        order.verify(ack).acknowledge();
        then(ack).should(never()).nack(any(Duration.class));
        final JsonNode body = objectMapper.readTree(sent().value());
        assertThat(body.path("sourceTopic").asText()).isEqualTo("incidents.lifecycle");
        assertThat(body.path("sourcePartition").asInt()).isEqualTo(2);
        assertThat(body.path("sourceOffset").asLong()).isEqualTo(41L);
        assertThat(body.path("originalPayload").asText()).isEqualTo("{poison}");
    }

    @Test
    @DisplayName("a copy Kafka did not take leaves the record unacknowledged, read again later; the stack "
            + "trace is logged once, then one WARN line per attempt (found in review)")
    void deadLetterFailedLeavesRecord() {
        kafkaFails();
        final Acknowledgment ack = mock(Acknowledgment.class);
        final ListAppender<ILoggingEvent> logged = captureLogs();

        assertThat(publisher.deadLetterThenAcknowledge(consumed(), null, "bad", ack)).isFalse();
        assertThat(publisher.deadLetterThenAcknowledge(consumed(), null, "bad", ack)).isFalse();

        then(ack).should(times(2)).nack(DeadLetterPublisher.REDELIVERY_DELAY);
        then(ack).should(never()).acknowledge();
        assertThat(redeliveries(DeadLetterPublisher.REASON_DEAD_LETTER_FAILED)).isEqualTo(2);
        assertThat(redeliveries(DeadLetterPublisher.REASON_TRANSIENT)).isZero();
        assertThat(logged.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.ERROR, Level.WARN);
        assertThat(logged.list.get(0).getThrowableProxy()).isNotNull();
        assertThat(logged.list.get(1).getThrowableProxy()).isNull();
        assertThat(logged.list.get(1).getFormattedMessage()).endsWith("cause=Unexpected error: IllegalStateException");
    }

    @Test
    @DisplayName("a transient failure is nacked and counted, nothing is dead-lettered (backlog #0-96)")
    void transientFailureRedelivered() {
        final Acknowledgment ack = mock(Acknowledgment.class);

        publisher.redeliverIfTransientElseDeadLetter(consumed(), "acme",
                new CannotCreateTransactionException("no connection"), ack);

        then(ack).should().nack(DeadLetterPublisher.REDELIVERY_DELAY);
        then(ack).should(never()).acknowledge();
        then(kafkaTemplate).shouldHaveNoInteractions();
        assertThat(redeliveries(DeadLetterPublisher.REASON_TRANSIENT)).isEqualTo(1);
    }

    @Test
    @DisplayName("a record still failing after its redelivery deadline is dead-lettered and counted, so it "
            + "cannot hold its partition for ever (found in review)")
    void givesUpAfterDeadline() throws Exception {
        kafkaAcknowledges();
        final Acknowledgment ack = mock(Acknowledgment.class);
        final RuntimeException timeout = new org.springframework.dao.QueryTimeoutException("slow every time");

        assertThat(publisher.redeliverLater(consumed(), "acme", ack, timeout)).isFalse();
        clock.advance(DEADLINE);
        assertThat(publisher.redeliverLater(consumed(), "acme", ack, timeout)).as("exactly at the deadline")
                .isFalse();
        then(kafkaTemplate).shouldHaveNoInteractions();

        clock.advance(Duration.ofSeconds(1));
        assertThat(publisher.redeliverLater(consumed(), "acme", ack, timeout)).isTrue();

        then(ack).should(times(2)).nack(DeadLetterPublisher.REDELIVERY_DELAY);
        then(ack).should().acknowledge();
        assertThat(gaveUp()).isEqualTo(1);
        final JsonNode body = objectMapper.readTree(sent().value());
        assertThat(body.path("errorReason").asText())
                .isEqualTo("still failing after PT30M: Unexpected error: QueryTimeoutException");
    }

    @Test
    @DisplayName("another offset failing on the partition starts its own deadline")
    void deadlinePerRecord() {
        final Acknowledgment ack = mock(Acknowledgment.class);
        final RuntimeException timeout = new org.springframework.dao.QueryTimeoutException("slow");

        publisher.redeliverLater(consumed(), "acme", ack, timeout);
        clock.advance(DEADLINE.plusMinutes(1));
        final ConsumerRecord<String, String> next = new ConsumerRecord<>("incidents.lifecycle", 2, 42L, "k", "{}");

        assertThat(publisher.redeliverLater(next, "acme", ack, timeout)).isFalse();
        then(kafkaTemplate).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("any other failure is dead-lettered by its type only, never its message, then acknowledged "
            + "(backlog #47, #0-96)")
    void unexpectedFailureDeadLettered() throws Exception {
        kafkaAcknowledges();
        final Acknowledgment ack = mock(Acknowledgment.class);
        final ListAppender<ILoggingEvent> logged = captureLogs();

        publisher.redeliverIfTransientElseDeadLetter(consumed(), "acme",
                new DataIntegrityViolationException("Key (tenant_id, secret)=(acme, top-secret) exists"), ack);

        then(ack).should().acknowledge();
        then(ack).should(never()).nack(any(Duration.class));
        assertThat(objectMapper.readTree(sent().value()).path("errorReason").asText())
                .isEqualTo("Unexpected error: DataIntegrityViolationException");
        assertThat(logged.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("top-secret");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    @DisplayName("past the deadline with a copy Kafka does not take: nacked, not counted as given up "
            + "(found in the second review)")
    void deadlinePassedButCopyFails() {
        kafkaFails();
        final Acknowledgment ack = mock(Acknowledgment.class);
        final RuntimeException timeout = new org.springframework.dao.QueryTimeoutException("slow every time");

        publisher.redeliverLater(consumed(), "acme", ack, timeout);
        clock.advance(DEADLINE.plusSeconds(1));

        assertThat(publisher.redeliverLater(consumed(), "acme", ack, timeout)).isFalse();

        then(ack).should(times(2)).nack(DeadLetterPublisher.REDELIVERY_DELAY);
        then(ack).should(never()).acknowledge();
        assertThat(gaveUp()).isZero();
        assertThat(redeliveries(DeadLetterPublisher.REASON_DEAD_LETTER_FAILED)).isEqualTo(1);
        assertThat(redeliveries(DeadLetterPublisher.REASON_TRANSIENT)).isEqualTo(1);
    }

    @Test
    @DisplayName("every counter is registered at zero, so the first one is visible to increase()")
    void countersRegisteredAtZero() {
        assertThat(redeliveries(DeadLetterPublisher.REASON_TRANSIENT)).isZero();
        assertThat(redeliveries(DeadLetterPublisher.REASON_DEAD_LETTER_FAILED)).isZero();
        assertThat(gaveUp()).isZero();
    }

    // ── configuration ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a whole poll of copies must fit in half of max.poll.interval.ms (found in review)")
    void pollIntervalBudget() {
        assertThatCode(() -> DeadLetterPublisher.requireFitsPollInterval(10, Duration.ofSeconds(120)))
                .doesNotThrowAnyException();
        assertThatCode(() -> DeadLetterPublisher.requireFitsPollInterval(10, Duration.ofSeconds(100)))
                .as("exactly half").doesNotThrowAnyException();
        assertThatThrownBy(() -> DeadLetterPublisher.requireFitsPollInterval(10, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("max.poll.interval.ms");
    }

    @Test
    @DisplayName("the dead-letter template keeps the service's producer settings, with a short metadata block")
    void deadLetterTemplate() throws Exception {
        final ProducerFactory<String, String> service = new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 60_000));

        final KafkaTemplate<String, String> template = DeadLetterPublisher.deadLetterTemplate(service);

        assertThat(template.getProducerFactory().getConfigurationProperties())
                .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG,
                        (int) DeadLetterPublisher.DEAD_LETTER_MAX_BLOCK.toMillis())
                .containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9");
        assertThat(service.getConfigurationProperties()).containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 60_000);
        new DeadLetterPublisher(template, objectMapper, "x.dead-letter", "s", meterRegistry).destroy();
    }

    @Test
    @DisplayName("a dead-letter topic not named *.dead-letter fails construction, so the service's start "
            + "(found in review)")
    void topicMustBeDeadLetter() {
        assertThatThrownBy(() -> new DeadLetterPublisher(kafkaTemplate, objectMapper, "incidents.dlq",
                "incident-service", meterRegistry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".dead-letter");
        assertThatThrownBy(() -> new DeadLetterPublisher(kafkaTemplate, objectMapper, null, "incident-service",
                meterRegistry))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeadLetterPublisher(kafkaTemplate, objectMapper, "x.dead-letter",
                "incident-service", meterRegistry, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
