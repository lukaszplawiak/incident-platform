package com.incidentplatform.escalation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;


@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${kafka.topics.incidents-lifecycle}")
    private String incidentsLifecycleTopic;

}