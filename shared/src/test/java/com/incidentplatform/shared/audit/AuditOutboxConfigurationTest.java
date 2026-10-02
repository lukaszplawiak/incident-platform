package com.incidentplatform.shared.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.core.LockProvider;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * The outbox's wiring (backlog #0-84, found in review): {@code audit.outbox.table}
 * turns it on, and with it the publisher, which writes there. Without the
 * property there is no publisher at all (#0-84's second step removed the direct
 * send), so a service that injects one fails at startup rather than losing events.
 */
@DisplayName("AuditOutboxConfiguration")
class AuditOutboxConfigurationTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withBean(JdbcTemplate.class, () -> jdbcTemplate)
            .withBean(LockProvider.class, () -> mock(LockProvider.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(ObjectMapper.class, () -> new ObjectMapper().registerModule(new JavaTimeModule()))
            .withBean(KafkaTemplate.class, () -> kafkaTemplate)
            .withUserConfiguration(AuditOutboxConfiguration.class);

    @Test
    @DisplayName("with audit.outbox.table: outbox and relay exist, and events go to the table")
    void onWithTable() {
        contexts.withPropertyValues("audit.outbox.table=auth_audit_outbox", "audit.outbox.batch-size=20")
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(AuditOutbox.class).hasSingleBean(AuditOutboxRelay.class);
                    assertThat(context.getBean(AuditOutboxProperties.class).batchSize()).isEqualTo(20);

                    context.getBean(AuditEventPublisher.class).publishAuth(UUID.randomUUID(), "acme",
                            AuditEventTypes.USER_LOGIN, "auth-service", "u", "Login", Map.of());

                    then(jdbcTemplate).should().update(eq("INSERT INTO auth_audit_outbox (id, tenant_id, "
                                    + "event_type, payload, status, attempts, created_at, next_attempt_at) "
                                    + "VALUES (?, ?, ?, ?, 'PENDING', 0, now(), now())"),
                            any(UUID.class), eq("acme"), eq(AuditEventTypes.USER_LOGIN), anyString());
                    then(kafkaTemplate).should(never()).send(any(ProducerRecord.class));
                });
    }

    @Test
    @DisplayName("without it: no outbox, no relay and no publisher (no direct send any more)")
    void offWithoutTable() {
        contexts.run(context -> {
            assertThat(context).hasNotFailed()
                    .doesNotHaveBean(AuditOutbox.class).doesNotHaveBean(AuditOutboxRelay.class)
                    .doesNotHaveBean(AuditEventPublisher.class).doesNotHaveBean(AuditEventKafkaSender.class);
            then(kafkaTemplate).should(never()).send(any(ProducerRecord.class));
        });
    }

    @Test
    @DisplayName("a service that injects the publisher without the property does not start")
    void publisherWithoutTableFailsStartup() {
        contexts.withBean(NeedsPublisher.class)
                .run(context -> assertThat(context).hasFailed());
    }

    /** Stands for a service class that records audit events. */
    static final class NeedsPublisher {
        NeedsPublisher(AuditEventPublisher publisher) {
        }
    }

    /**
     * Found in review: the services get their KafkaTemplate from Spring
     * Boot's auto-configuration, typed {@code KafkaTemplate<?, ?>}, while the
     * sender asks for {@code KafkaTemplate<String, String>}. With Boot's own
     * beans (no broker needed to create them) the outbox, the relay and the
     * publisher must all come up.
     */
    @Test
    @DisplayName("with Spring Boot's own Kafka and Jackson beans, the publisher and relay are created")
    void wiresWithBootAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class,
                        JacksonAutoConfiguration.class))
                .withBean(JdbcTemplate.class, () -> jdbcTemplate)
                .withBean(LockProvider.class, () -> mock(LockProvider.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withUserConfiguration(AuditOutboxConfiguration.class)
                .withPropertyValues("audit.outbox.table=notification_audit_outbox",
                        "spring.kafka.bootstrap-servers=localhost:1")
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(AuditEventPublisher.class)
                        .hasSingleBean(AuditOutboxRelay.class)
                        .hasSingleBean(AuditEventKafkaSender.class));
    }

    @Test
    @DisplayName("a table name that is not a plain identifier stops the startup")
    void badTableName() {
        contexts.withPropertyValues("audit.outbox.table=audit; drop table users")
                .run(context -> assertThat(context).hasFailed());
    }
}
