package com.incidentplatform.shared.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * turns it on, and the publisher then writes there instead of sending; without
 * the property no outbox bean exists and the publisher sends as before. A
 * renamed property would otherwise fall back to sending without any test failing.
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
            .withBean(AuditEventKafkaSender.class, () -> new AuditEventKafkaSender(kafkaTemplate,
                    new ObjectMapper().registerModule(new JavaTimeModule()), "audit.events"))
            .withUserConfiguration(AuditOutboxConfiguration.class, AuditEventPublisher.class);

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
                    then(kafkaTemplate).shouldHaveNoInteractions();
                });
    }

    @Test
    @DisplayName("without it: no outbox, and the publisher sends directly")
    void offWithoutTable() {
        contexts.run(context -> {
            assertThat(context).hasNotFailed()
                    .doesNotHaveBean(AuditOutbox.class).doesNotHaveBean(AuditOutboxRelay.class);

            context.getBean(AuditEventPublisher.class).publishAuth(UUID.randomUUID(), "acme",
                    AuditEventTypes.USER_LOGIN, "auth-service", "u", "Login", Map.of());

            then(kafkaTemplate).should().send(any(org.apache.kafka.clients.producer.ProducerRecord.class));
            then(jdbcTemplate).should(never()).update(anyString(), any(Object[].class));
        });
    }

    @Test
    @DisplayName("a table name that is not a plain identifier stops the startup")
    void badTableName() {
        contexts.withPropertyValues("audit.outbox.table=audit; drop table users")
                .run(context -> assertThat(context).hasFailed());
    }
}
