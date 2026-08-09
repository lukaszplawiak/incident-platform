package com.incidentplatform.postmortem.config;

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

    private final int listenerConcurrency;
    private final String postmortemDeadLetterTopic;
    private final int deadLetterRetentionDays;

    // Constructor injection instead of field-injected @Value — consistent
    // with the platform's general preference for constructor injection
    // (testable, allows final fields, dependencies visible in one place).
    public KafkaConfig(
            @Value("${spring.kafka.listener.concurrency:3}") int listenerConcurrency,
            @Value("${kafka.topics.postmortem-dead-letter:postmortem.dead-letter}")
            String postmortemDeadLetterTopic,
            @Value("${kafka.dead-letter.retention-days:30}") int deadLetterRetentionDays) {
        this.listenerConcurrency = listenerConcurrency;
        this.postmortemDeadLetterTopic = postmortemDeadLetterTopic;
        this.deadLetterRetentionDays = deadLetterRetentionDays;
    }

    /**
     * Overrides Spring Boot's auto-configured {@code kafkaListenerContainerFactory}
     * to register {@link TenantKafkaRecordInterceptor} for MDC enrichment,
     * structured observability logging and per-tenant Micrometer metrics.
     *
     * <p>All other Kafka settings (bootstrap servers, deserializers, group-id,
     * auto-offset-reset etc.) are read from {@code application.yml} by the
     * auto-configured {@link ConsumerFactory}.
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

    @Bean
    public TenantKafkaRecordInterceptor<String, String> tenantKafkaRecordInterceptor(
            MeterRegistry meterRegistry) {
        return new TenantKafkaRecordInterceptor<>(meterRegistry);
    }

    // ── Dead-letter topic ────────────────────────────────────────────────────
    //
    // Own topic, not shared with incident-service's incidents.dead-letter —
    // DeadLetterPublisher's own Javadoc documents one DLT topic per service
    // ("specific to that service"). Keeping poison pills from four different
    // consumers (incident/escalation/notification/postmortem-service, all
    // consuming incidents.lifecycle) separated by topic makes them easier to
    // triage than relying solely on the sourceService field inside a shared
    // topic's payload.

    @Bean
    public NewTopic postmortemDeadLetterTopic() {
        return TopicBuilder
                .name(postmortemDeadLetterTopic)
                .partitions(1)
                .replicas(1)
                .config("retention.ms",
                        String.valueOf((long) deadLetterRetentionDays * 24 * 60 * 60 * 1000))
                .build();
    }

    /**
     * Dead-letter publisher for IncidentEventConsumer — handles poison pills
     * (permanently malformed messages, or messages with a severity value this
     * service version doesn't recognize) in MANUAL_IMMEDIATE ack mode.
     *
     * <p>Previously these cases were only logged and acknowledged — the
     * message itself was discarded with no way to inspect or replay it.
     * Mirrors incident-service's DeadLetterPublisher wiring exactly.
     */
    @Bean
    public DeadLetterPublisher deadLetterPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        return new DeadLetterPublisher(
                kafkaTemplate,
                objectMapper,
                postmortemDeadLetterTopic,
                "postmortem-service"
        );
    }
}