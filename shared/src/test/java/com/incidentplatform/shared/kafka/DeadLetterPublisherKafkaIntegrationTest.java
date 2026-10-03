package com.incidentplatform.shared.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.CannotCreateTransactionException;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@link DeadLetterPublisher} on a real broker, and why it exists: what a
 * listener in {@code AckMode.MANUAL_IMMEDIATE} — every consumer of the
 * platform — must do with a record it could not process (backlog #0-96).
 *
 * <p>The consumers used to {@code return} without acknowledging on a transient
 * error, expecting Kafka to deliver the record again. It does not: the
 * container goes on to the next record, and that record's acknowledgement
 * commits the partition's offset past the one left out, so the record is gone
 * unless the service restarts before anything later on the partition is
 * acknowledged. Only {@code nack} (or a thrown exception with an error
 * handler) seeks the partition back to it.
 */
@DisplayName("Manual acknowledgement on a real broker (backlog #0-96)")
class DeadLetterPublisherKafkaIntegrationTest {

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.2");

    private static AdminClient admin;
    private static KafkaTemplate<String, String> template;

    @BeforeAll
    static void start() {
        KAFKA.start();
        admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
        template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
    }

    @AfterAll
    static void stop() {
        if (admin != null) {
            admin.close();
        }
        KAFKA.stop();
    }

    @Test
    @DisplayName("a record left unacknowledged is skipped once a later one is acknowledged")
    void unacknowledgedRecordIsSkipped() throws Exception {
        final String topic = topicWithTwoRecords();
        final String group = "skip-" + UUID.randomUUID();
        final List<Long> seen = new CopyOnWriteArrayList<>();

        runUntil(topic, group, seen, (record, ack) -> {
            if (record.offset() != 0) {
                ack.acknowledge();
            }
            // offset 0: the old "transient error — do NOT acknowledge" branch
        }, () -> seen.contains(1L));
        await().atMost(Duration.ofSeconds(30)).until(() -> committed(topic, group) == 2);

        // A fresh consumer of the group — what "Kafka will redeliver after a
        // restart" was counting on — gets only what comes after: a sentinel
        // record is the first it sees, record 0 never (no waiting on a clock
        // to show that nothing arrives).
        template.send(topic, "k", "sentinel").get(30, TimeUnit.SECONDS);
        final List<Long> afterRestart = new CopyOnWriteArrayList<>();
        runUntil(topic, group, afterRestart, (record, ack) -> ack.acknowledge(), () -> afterRestart.contains(2L));
        assertThat(seen).containsExactly(0L, 1L);
        assertThat(afterRestart).containsExactly(2L);
    }

    @Test
    @DisplayName("a nacked record comes again before the records after it")
    void nackedRecordComesAgain() throws Exception {
        final String topic = topicWithTwoRecords();
        final String group = "nack-" + UUID.randomUUID();
        final List<Long> seen = new CopyOnWriteArrayList<>();

        runUntil(topic, group, seen, (record, ack) -> {
            if (record.offset() == 0 && seen.stream().filter(o -> o == 0L).count() == 1) {
                ack.nack(Duration.ofMillis(100));
            } else {
                ack.acknowledge();
            }
        }, () -> seen.contains(1L));

        assertThat(seen).containsExactly(0L, 0L, 1L);
        await().atMost(Duration.ofSeconds(30)).until(() -> committed(topic, group) == 2);
    }

    @Test
    @DisplayName("DeadLetterPublisher: a transient failure comes again, a poison pill is copied, then acknowledged")
    void platformHelpers() throws Exception {
        final String topic = topicWithTwoRecords();
        final String deadLetterTopic = "test-" + UUID.randomUUID() + ".dead-letter";
        final String group = "helpers-" + UUID.randomUUID();
        final DeadLetterPublisher publisher = new DeadLetterPublisher(
                template, new ObjectMapper(), deadLetterTopic, "test-service", new SimpleMeterRegistry());
        final List<Long> seen = new CopyOnWriteArrayList<>();

        runUntil(topic, group, seen, (record, ack) -> {
            if (record.offset() != 0) {
                ack.acknowledge();
            } else if (seen.size() == 1) {
                publisher.redeliverIfTransientElseDeadLetter(record, "acme",
                        new CannotCreateTransactionException("database down"), ack);
            } else {
                publisher.redeliverIfTransientElseDeadLetter(record, "acme",
                        new IllegalStateException("poison"), ack);
            }
        }, () -> seen.contains(1L));

        assertThat(seen).containsExactly(0L, 0L, 1L);
        await().atMost(Duration.ofSeconds(30)).until(() -> committed(topic, group) == 2);
        final List<String> copies = new CopyOnWriteArrayList<>();
        final KafkaMessageListenerContainer<String, String> reader = container(deadLetterTopic,
                "reader-" + UUID.randomUUID(), new CopyOnWriteArrayList<>(), (record, ack) -> {
                    copies.add(record.value());
                    ack.acknowledge();
                });
        reader.start();
        try {
            await().atMost(Duration.ofSeconds(60)).until(() -> !copies.isEmpty());
        } finally {
            reader.stop();
        }
        assertThat(copies).singleElement().satisfies(copy -> assertThat(copy)
                .contains("\"sourceOffset\":0").contains("\"originalPayload\":\"first\""));
    }

    private static String topicWithTwoRecords() throws Exception {
        final String topic = "ack-" + UUID.randomUUID();
        admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
        template.send(topic, "k", "first").get(30, TimeUnit.SECONDS);
        template.send(topic, "k", "second").get(30, TimeUnit.SECONDS);
        return topic;
    }

    private static long committed(String topic, String group) throws Exception {
        final OffsetAndMetadata offset = admin.listConsumerGroupOffsets(group)
                .partitionsToOffsetAndMetadata().get(30, TimeUnit.SECONDS)
                .get(new TopicPartition(topic, 0));
        return offset == null ? -1 : offset.offset();
    }

    private static void runUntil(String topic, String group, List<Long> seen,
                                 BiConsumer<ConsumerRecord<String, String>, Acknowledgment> handler,
                                 BooleanSupplier done) {
        final KafkaMessageListenerContainer<String, String> container = container(topic, group, seen, handler);
        container.start();
        try {
            await().atMost(Duration.ofSeconds(60)).until(done::getAsBoolean);
        } finally {
            container.stop();
        }
    }

    private static KafkaMessageListenerContainer<String, String> container(
            String topic, String group, List<Long> seen,
            BiConsumer<ConsumerRecord<String, String>, Acknowledgment> handler) {
        final DefaultKafkaConsumerFactory<String, String> consumers = new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        final ContainerProperties properties = new ContainerProperties(topic);
        properties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        properties.setMessageListener((AcknowledgingMessageListener<String, String>) (record, ack) -> {
            seen.add(record.offset());
            handler.accept(record, ack);
        });
        return new KafkaMessageListenerContainer<>(consumers, properties);
    }
}
