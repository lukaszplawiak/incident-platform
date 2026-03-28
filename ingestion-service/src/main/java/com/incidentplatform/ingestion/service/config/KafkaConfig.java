package com.incidentplatform.ingestion_service.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Value("${kafka.topics.alerts-raw}")
    private String alertsRawTopic;

    @Value("${kafka.topics.alerts-resolved}")
    private String alertsResolvedTopic;

    @Value("${kafka.topics.alerts-dead-letter}")
    private String alertsDeadLetterTopic;

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
    public NewTopic alertsDeadLetterTopic() {
        return TopicBuilder
                .name(alertsDeadLetterTopic)
                .partitions(1)
                .replicas(1)
                .config("retention.ms",
                        String.valueOf(30L * 24 * 60 * 60 * 1000))
                .build();
    }
}