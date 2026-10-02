package com.incidentplatform.shared.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.core.LockProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Clock;

/**
 * The audit outbox of a service that sets {@code audit.outbox.table}
 * (backlog #0-84), and with it the only {@link AuditEventPublisher}. A service
 * without the property (ingestion- and oncall-service, which record no audit
 * events) gets none of these beans; one that injects the publisher anyway
 * fails at startup, so no event is ever sent around the outbox (#0-84's
 * second step removed that direct path). The service provides the
 * {@link LockProvider} (its ShedLock configuration), the {@link JdbcTemplate},
 * the {@link KafkaTemplate} and scheduling.
 *
 * <p>{@code spring-jdbc} and {@code shedlock-spring} are optional in
 * {@code shared}: a service that sets the property must have both (a
 * database and ShedLock), or it fails at startup loading this class. That is
 * on purpose: a {@code @ConditionalOnClass} would instead turn the outbox off
 * without a word and fall back to sending directly, the loss #0-84 removes
 * (found in review). ingestion-service has neither and sets no table.
 */
@Configuration
@ConditionalOnProperty(prefix = "audit.outbox", name = "table")
@EnableConfigurationProperties(AuditOutboxProperties.class)
public class AuditOutboxConfiguration {

    @Bean
    AuditEventKafkaSender auditEventKafkaSender(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${kafka.topics.audit-events:audit.events}") String auditEventsTopic) {
        return new AuditEventKafkaSender(kafkaTemplate, objectMapper, auditEventsTopic);
    }

    @Bean
    public AuditEventPublisher auditEventPublisher(AuditEventKafkaSender sender, AuditOutbox auditOutbox) {
        return new AuditEventPublisher(sender, auditOutbox);
    }

    @Bean
    public AuditOutbox auditOutbox(JdbcTemplate jdbcTemplate, AuditOutboxProperties properties) {
        return new AuditOutbox(jdbcTemplate, properties.table());
    }

    @Bean
    public AuditOutboxRelay auditOutboxRelay(AuditOutbox auditOutbox,
                                             AuditEventKafkaSender sender,
                                             AuditOutboxProperties properties,
                                             LockProvider lockProvider,
                                             MeterRegistry meterRegistry) {
        return new AuditOutboxRelay(auditOutbox, sender, properties, lockProvider, meterRegistry,
                Clock.systemUTC());
    }
}
