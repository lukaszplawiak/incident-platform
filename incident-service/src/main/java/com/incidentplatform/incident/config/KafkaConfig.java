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

@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${kafka.topics.incidents-lifecycle}")
    private String incidentsLifecycleTopic;

    @Value("${kafka.topics.alerts-raw}")
    private String alertsRawTopic;

    @Value("${kafka.topics.alerts-resolved}")
    private String alertsResolvedTopic;

    @Value("${kafka.topics.audit-events}")
    private String auditEventsTopic;

    @Value("${kafka.topics.incidents-dead-letter:incidents.dead-letter}")
    private String incidentsDeadLetterTopic;

    @Value("${spring.kafka.listener.concurrency:3}")
    private int listenerConcurrency;

    @Value("${kafka.dead-letter.retention-days:30}")
    private int deadLetterRetentionDays;

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
     *   - poison pill (acknowledge + DLT) — always fails, skip immediately
     *   - transient error (no acknowledge) — may recover, Kafka redelivers
     * DefaultErrorHandler with DeadLetterPublishingRecoverer was removed because
     * it only works with AUTO ack mode. In MANUAL_IMMEDIATE mode the listener
     * owns offset management and DefaultErrorHandler is never invoked.
     */
    @Bean
    public DeadLetterPublisher deadLetterPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        return new DeadLetterPublisher(
                kafkaTemplate,
                objectMapper,
                incidentsDeadLetterTopic,
                "incident-service"
        );
    }
}