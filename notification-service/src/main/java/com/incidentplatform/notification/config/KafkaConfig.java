package com.incidentplatform.notification.config;

import com.incidentplatform.shared.kafka.TenantKafkaRecordInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${spring.kafka.listener.concurrency:3}")
    private int listenerConcurrency;

    /**
     * Overrides Spring Boot's auto-configured {@code kafkaListenerContainerFactory}
     * to register {@link TenantKafkaRecordInterceptor} for MDC enrichment,
     * structured observability logging and per-tenant Micrometer metrics.
     *
     * <p>All other Kafka settings (bootstrap servers, deserializers, group-id,
     * auto-offset-reset, max.poll.interval.ms etc.) are read from
     * {@code application.yml} by the auto-configured {@link ConsumerFactory}.
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
}