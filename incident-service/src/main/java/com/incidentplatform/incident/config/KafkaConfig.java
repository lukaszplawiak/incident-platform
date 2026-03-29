package com.incidentplatform.incident.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Value("${kafka.topics.incidents-lifecycle}")
    private String incidentsLifecycleTopic;

    @Bean
    public NewTopic incidentsLifecycleTopic() {
        return TopicBuilder
                .name(incidentsLifecycleTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}