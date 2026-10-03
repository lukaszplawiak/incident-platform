package com.incidentplatform.incident.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.shared.kafka.DeadLetterPublisher;
import com.incidentplatform.shared.kafka.TenantKafkaRecordInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;

import java.time.Duration;

@Configuration
@EnableKafka
public class KafkaConfig {

    private final String incidentsLifecycleTopic;
    private final String alertsRawTopic;
    private final String alertsResolvedTopic;
    private final String auditEventsTopic;
    private final String incidentsDeadLetterTopic;
    private final int listenerConcurrency;
    private final int deadLetterRetentionDays;

    // Constructor injection instead of field-injected @Value — consistent
    // with AlertKafkaProducer (ingestion-service) and the platform's general
    // preference for constructor injection over field injection (easier to
    // unit-test, fields can be final, dependencies are visible in one place).
    public KafkaConfig(
            @Value("${kafka.topics.incidents-lifecycle}") String incidentsLifecycleTopic,
            @Value("${kafka.topics.alerts-raw}") String alertsRawTopic,
            @Value("${kafka.topics.alerts-resolved}") String alertsResolvedTopic,
            @Value("${kafka.topics.audit-events}") String auditEventsTopic,
            @Value("${kafka.topics.incidents-dead-letter:incidents.dead-letter}")
            String incidentsDeadLetterTopic,
            @Value("${spring.kafka.listener.concurrency:3}") int listenerConcurrency,
            @Value("${kafka.dead-letter.retention-days:30}") int deadLetterRetentionDays) {
        this.incidentsLifecycleTopic = incidentsLifecycleTopic;
        this.alertsRawTopic = alertsRawTopic;
        this.alertsResolvedTopic = alertsResolvedTopic;
        this.auditEventsTopic = auditEventsTopic;
        this.incidentsDeadLetterTopic = incidentsDeadLetterTopic;
        this.listenerConcurrency = listenerConcurrency;
        this.deadLetterRetentionDays = deadLetterRetentionDays;
    }

    // ── Topic definitions ─────────────────────────────────────────────────────

    @Bean
    public NewTopic incidentsLifecycleTopic() {
        return TopicBuilder
                .name(incidentsLifecycleTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic alertsRawTopic() {
        return TopicBuilder
                .name(alertsRawTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic alertsResolvedTopic() {
        return TopicBuilder
                .name(alertsResolvedTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic auditEventsTopic() {
        return TopicBuilder
                .name(auditEventsTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic incidentsDeadLetterTopic() {
        return TopicBuilder
                .name(incidentsDeadLetterTopic)
                .partitions(1)
                .replicas(1)
                .config("retention.ms",
                        String.valueOf((long) deadLetterRetentionDays * 24 * 60 * 60 * 1000))
                .build();
    }

    // ── Listener container factory ────────────────────────────────────────────

    /**
     * Overrides the Spring Boot auto-configured {@code kafkaListenerContainerFactory}
     * to register {@link TenantKafkaRecordInterceptor}.
     *
     * <p>{@link TenantKafkaRecordInterceptor} runs on the Spring listener thread
     * (unlike {@code ConsumerInterceptor} which runs on the Kafka poll thread)
     * so it can safely set MDC keys, record Micrometer timers, and log structured
     * observability events that are visible throughout the entire record processing
     * pipeline.
     *
     * <p>All other settings (ack-mode, concurrency, deserializers) are preserved
     * from {@code application.yml} via the auto-configured {@link ConsumerFactory}.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String>
    kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            TenantKafkaRecordInterceptor<String, String> recordInterceptor) {

        final ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(listenerConcurrency);
        factory.getContainerProperties()
                .setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setRecordInterceptor(recordInterceptor);
        return factory;
    }

    // ── Infrastructure beans ──────────────────────────────────────────────────

    /**
     * Per-service {@link TenantKafkaRecordInterceptor} bean.
     * Declared here (not {@code @Component} in shared) so each service gets
     * its own instance with its own Micrometer meter registry — consistent
     * with how {@link DeadLetterPublisher} is declared.
     */
    @Bean
    public TenantKafkaRecordInterceptor<String, String> tenantKafkaRecordInterceptor(
            MeterRegistry meterRegistry) {
        return new TenantKafkaRecordInterceptor<>(meterRegistry);
    }

    /**
     * Dead-letter publisher for IncidentKafkaConsumer — handles poison pills
     * (permanently malformed messages) in MANUAL_IMMEDIATE ack mode.
     * We use MANUAL ack to distinguish:
     *   - poison pill — copied to the DLT, acknowledged once Kafka has the copy
     *     ({@link DeadLetterPublisher#deadLetterThenAcknowledge})
     *   - transient error — {@code nack}ed, read again after a delay
     *     ({@link DeadLetterPublisher#redeliverLater})
     *
     * <p>Changed (backlog #0-96): this said DefaultErrorHandler "is never
     * invoked" in MANUAL_IMMEDIATE mode. It is, whenever a listener throws;
     * the listeners here simply never throw, they decide each record's fate
     * themselves. A transient error used to be "no acknowledge, Kafka
     * redelivers", which it does not: the next record's acknowledgement
     * commits the offset past it. Moving the consumers onto DefaultErrorHandler
     * with a dead-letter recoverer is backlog #0-97.
     *
     * <p>Backlog #0-96 (found in review): the publisher sends with a producer of
     * its own, which blocks at most {@link DeadLetterPublisher#DEAD_LETTER_MAX_BLOCK}
     * for Kafka's metadata, so a copy waits at most
     * {@link DeadLetterPublisher#DEAD_LETTER_TIMEOUT} in all; the service
     * does not start unless a whole poll of such waits fits in
     * {@code max.poll.interval.ms}; and a record that keeps failing is
     * dead-lettered after {@code kafka.consumer.redelivery-deadline}.
     */
    @Bean
    public DeadLetterPublisher deadLetterPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${kafka.consumer.redelivery-deadline:PT30M}") Duration redeliveryDeadline,
            @Value("${spring.kafka.consumer.properties.max.poll.records}") int maxPollRecords,
            @Value("${spring.kafka.consumer.properties.max.poll.interval.ms}") long maxPollIntervalMs) {
        DeadLetterPublisher.requireFitsPollInterval(maxPollRecords, Duration.ofMillis(maxPollIntervalMs));
        return new DeadLetterPublisher(
                DeadLetterPublisher.deadLetterTemplate(kafkaTemplate.getProducerFactory()),
                objectMapper,
                incidentsDeadLetterTopic,
                "incident-service",
                meterRegistry,
                redeliveryDeadline
        );
    }
}