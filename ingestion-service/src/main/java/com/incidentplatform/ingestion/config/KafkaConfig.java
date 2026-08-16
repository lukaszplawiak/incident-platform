package com.incidentplatform.ingestion.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.shared.kafka.DeadLetterPublisher;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration
public class KafkaConfig {

    private final String alertsRawTopic;
    private final String alertsResolvedTopic;
    private final String alertsDeadLetterTopic;
    private final int deadLetterRetentionDays;
    private final int topicPartitions;
    private final int topicReplicas;
    private final int deadLetterTopicPartitions;

    // Constructor injection instead of field-injected @Value — consistent
    // with the platform's general preference for constructor injection
    // (testable, allows final fields, dependencies visible in one place).
    // Same pattern already established in postmortem-service's,
    // oncall-service's, and other modules' KafkaConfig — this was the one
    // remaining KafkaConfig still using field injection.
    //
    // Fixed (backlog #31): partitions/replicas were previously hardcoded
    // here (partitions=3/replicas=1 for alerts-raw and alerts-resolved;
    // partitions=1/replicas=1 for alerts-dead-letter) — fine for
    // local/CI docker-compose (a single Kafka broker, where replicas > 1
    // wouldn't even be possible), but zero broker-failure resilience if
    // the exact same values carried over unmodified to a production
    // cluster with more brokers available. Now configurable via
    // application.yml's kafka.topic-config.* — see that section's own
    // comment for the full account, including why the defaults below
    // match the previous hardcoded values exactly (zero behavior change
    // unless explicitly overridden) and why this class doesn't guess a
    // higher default itself.
    public KafkaConfig(
            @Value("${kafka.topics.alerts-raw}") String alertsRawTopic,
            @Value("${kafka.topics.alerts-resolved}") String alertsResolvedTopic,
            @Value("${kafka.topics.alerts-dead-letter}") String alertsDeadLetterTopic,
            @Value("${kafka.dead-letter.retention-days:30}") int deadLetterRetentionDays,
            @Value("${kafka.topic-config.partitions:3}") int topicPartitions,
            @Value("${kafka.topic-config.replicas:1}") int topicReplicas,
            @Value("${kafka.topic-config.dead-letter-partitions:1}")
            int deadLetterTopicPartitions) {
        this.alertsRawTopic = alertsRawTopic;
        this.alertsResolvedTopic = alertsResolvedTopic;
        this.alertsDeadLetterTopic = alertsDeadLetterTopic;
        this.deadLetterRetentionDays = deadLetterRetentionDays;
        this.topicPartitions = topicPartitions;
        this.topicReplicas = topicReplicas;
        this.deadLetterTopicPartitions = deadLetterTopicPartitions;
    }

    @Bean
    public NewTopic alertsRawTopic() {
        return TopicBuilder
                .name(alertsRawTopic)
                .partitions(topicPartitions)
                .replicas(topicReplicas)
                .build();
    }

    @Bean
    public NewTopic alertsResolvedTopic() {
        return TopicBuilder
                .name(alertsResolvedTopic)
                .partitions(topicPartitions)
                .replicas(topicReplicas)
                .build();
    }

    @Bean
    public NewTopic alertsDeadLetterTopic() {
        return TopicBuilder
                .name(alertsDeadLetterTopic)
                .partitions(deadLetterTopicPartitions)
                .replicas(topicReplicas)
                .config("retention.ms",
                        String.valueOf((long) deadLetterRetentionDays * 24 * 60 * 60 * 1000))
                .build();
    }

    // DeadLetterPublisher moved from ingestion-service/service/ to shared module.
    // Instantiated here (not @Component) so each service can provide its own
    // topic name and service name without property name conflicts across services.
    @Bean
    public DeadLetterPublisher deadLetterPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        return new DeadLetterPublisher(
                kafkaTemplate,
                objectMapper,
                alertsDeadLetterTopic,
                "ingestion-service"
        );
    }
}