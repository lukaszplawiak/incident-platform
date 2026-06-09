package com.incidentplatform.incident.config;

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
                        String.valueOf(30L * 24 * 60 * 60 * 1000))
                .build();
    }

    // Dead-letter publisher for IncidentKafkaConsumer — handles poison pills
    // (permanently malformed messages) in MANUAL_IMMEDIATE ack mode.
    // We use MANUAL ack to distinguish:
    //   - poison pill (acknowledge + DLT) — always fails, skip immediately
    //   - transient error (no acknowledge) — may recover, Kafka redelivers
    // DefaultErrorHandler with DeadLetterPublishingRecoverer was removed because
    // it only works with AUTO ack mode. In MANUAL_IMMEDIATE mode the listener
    // owns offset management and DefaultErrorHandler is never invoked.
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