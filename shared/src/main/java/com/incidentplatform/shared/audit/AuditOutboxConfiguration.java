package com.incidentplatform.shared.audit;

import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.core.LockProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

/**
 * The audit outbox of a service that sets {@code audit.outbox.table}
 * (backlog #0-84). A service without it (one not yet moved, or one without a
 * database) gets neither bean, and {@link AuditEventPublisher} sends directly.
 * The service provides the {@link LockProvider} (its ShedLock configuration),
 * the {@link JdbcTemplate} and scheduling.
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
