package com.incidentplatform.incident.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    @Value("${kafka.topics.incidents-lifecycle}")
    private String incidentsLifecycleTopic;

    @Value("${kafka.topics.alerts-raw}")
    private String alertsRawTopic;

    @Value("${kafka.topics.alerts-resolved}")
    private String alertsResolvedTopic;

    @Bean
    public NewTopic incidentsLifecycleTopic() {
        return TopicBuilder
                .name(incidentsLifecycleTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic alertsRawDltTopic() {
        return TopicBuilder
                .name(alertsRawTopic + ".DLT")
                .partitions(1)
                .replicas(1)
                .config("retention.ms",
                        String.valueOf(30L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    public NewTopic alertsResolvedDltTopic() {
        return TopicBuilder
                .name(alertsResolvedTopic + ".DLT")
                .partitions(1)
                .replicas(1)
                .config("retention.ms",
                        String.valueOf(30L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    public DefaultErrorHandler defaultErrorHandler(
            KafkaOperations<String, String> kafkaTemplate) {

        final DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(
                        kafkaTemplate,
                        (record, exception) -> new TopicPartition(
                                record.topic() + ".DLT", 0)
                );

        final FixedBackOff backOff = new FixedBackOff(1000L, 3L);

        return new DefaultErrorHandler(recoverer, backOff);
    }
}