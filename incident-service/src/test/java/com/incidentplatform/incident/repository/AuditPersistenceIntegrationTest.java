package com.incidentplatform.incident.repository;

import com.incidentplatform.incident.domain.AuditEvent;
import com.incidentplatform.incident.kafka.AuditEventConsumer;
import com.incidentplatform.shared.audit.AuditOutbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * incident-service's audit tables on a real Postgres (backlog #0-84):
 * Flyway runs V1..V14 (V13 outside a transaction, CONCURRENTLY), Hibernate
 * validates {@link AuditEvent} against them ({@code ddl-auto: validate}, as in
 * production), V13's index makes an outbox resend a duplicate the consumer
 * recognises, the shared {@link AuditOutbox} SQL fits V14's table, and its
 * writes join the service's JPA transaction. The first test of this module on
 * a real database.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
@DisplayName("Audit persistence (Postgres, backlog #0-84)")
class AuditPersistenceIntegrationTest {

    /**
     * Its own, narrow configuration: the main application class scans test
     * packages too and would pick up other tests' configurations.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = AuditEvent.class)
    @EnableJpaRepositories(basePackageClasses = AuditEventRepository.class)
    static class TestConfig {
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private org.springframework.core.env.Environment environment;

    /**
     * The outbox on the table the service's application.yml names (found in
     * review: a hard-coded name would let a typo in the property pass every
     * test and fail every audited action at runtime).
     */
    private AuditOutbox configuredOutbox() {
        final String table = environment.getRequiredProperty("audit.outbox.table");
        assertThat(table).isEqualTo("incident_audit_outbox");
        return new AuditOutbox(jdbcTemplate, table);
    }

    private AuditEvent event(UUID eventId, long offset) {
        return AuditEvent.system(UUID.randomUUID(), "acme", "INCIDENT_CREATED", "incident-service",
                "Created", Map.of(), 0, offset, eventId, Instant.parse("2026-10-03T09:00:00Z"));
    }

    private AuditEvent event(String tenantId, UUID eventId, long offset) {
        return AuditEvent.system(UUID.randomUUID(), tenantId, "INCIDENT_CREATED", "incident-service",
                "Created", Map.of(), 0, offset, eventId, Instant.parse("2026-10-03T09:00:00Z"));
    }

    @Test
    @DisplayName("the same event id twice, as two records (an outbox resend), is a duplicate the consumer recognises")
    void eventIdDeduplicates() {
        final UUID eventId = UUID.randomUUID();
        auditEventRepository.saveAndFlush(event(eventId, 1));

        assertThatThrownBy(() -> auditEventRepository.saveAndFlush(event(eventId, 2)))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class, e ->
                        assertThat(AuditEventConsumer.violatedConstraint(e))
                                .isIn(AuditEventConsumer.IDEMPOTENCY_KEYS)
                                .isEqualTo("uq_audit_events_tenant_event_id"));
    }

    @Test
    @DisplayName("a Kafka redelivery (same partition and offset) is recognised as a duplicate as well")
    void partitionOffsetDeduplicates() {
        auditEventRepository.saveAndFlush(event(null, 30));

        assertThatThrownBy(() -> auditEventRepository.saveAndFlush(event(null, 30)))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class, e ->
                        assertThat(AuditEventConsumer.violatedConstraint(e))
                                .isEqualTo("uq_audit_events_kafka_partition_offset"));
    }

    @Test
    @DisplayName("an event id is unique per tenant: another tenant's event cannot pre-empt it")
    void eventIdPerTenant() {
        final UUID eventId = UUID.randomUUID();
        auditEventRepository.saveAndFlush(event("acme", eventId, 40));
        auditEventRepository.saveAndFlush(event("globex", eventId, 41));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_events WHERE event_id = ?",
                Integer.class, eventId)).isEqualTo(2);
    }

    @Test
    @DisplayName("V13 built its index CONCURRENTLY and valid")
    void indexValid() {
        assertThat(jdbcTemplate.queryForObject("SELECT i.indisvalid FROM pg_index i "
                + "JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'uq_audit_events_tenant_event_id'",
                Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("events without an id (producers before the outbox) never conflict with each other")
    void legacyEventsWithoutId() {
        auditEventRepository.saveAndFlush(event(null, 10));
        auditEventRepository.saveAndFlush(event(null, 11));

        assertThat(auditEventRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("the event's own time is stored, not the time it was consumed")
    void occurredAtStored() {
        final AuditEvent saved = auditEventRepository.saveAndFlush(event(UUID.randomUUID(), 20));

        assertThat(jdbcTemplate.queryForObject("SELECT occurred_at FROM audit_events WHERE id = ?",
                java.sql.Timestamp.class, saved.getId()).toInstant())
                .isEqualTo(Instant.parse("2026-10-03T09:00:00Z"));
    }

    @Test
    @DisplayName("V14's table takes the shared outbox SQL: write, due, sent, failed, backlog")
    void outboxTableFitsSharedSql() {
        final AuditOutbox outbox = configuredOutbox();
        final UUID sent = UUID.randomUUID();
        final UUID failed = UUID.randomUUID();

        outbox.enqueue(sent, "acme", "INCIDENT_CREATED", "{\"eventId\":\"" + sent + "\"}");
        outbox.enqueue(failed, "acme", "INCIDENT_CREATED", "{}");

        assertThat(jdbcTemplate.queryForMap("SELECT status, attempts, tenant_id FROM incident_audit_outbox "
                + "WHERE id = ?", sent))
                .containsEntry("status", "PENDING")
                .containsEntry("attempts", 0)
                .containsEntry("tenant_id", "acme");
        assertThat(outbox.due(10)).extracting(AuditOutbox.Pending::id).containsExactlyInAnyOrder(sent, failed);
        assertThat(outbox.markSent(java.util.List.of(sent))).isEqualTo(1);
        assertThat(outbox.markFailed(failed, java.time.Duration.ofSeconds(60), "down")).isTrue();
        assertThat(outbox.due(10)).isEmpty();
        assertThat(outbox.backlog().pending()).isEqualTo(1);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE incident_audit_outbox SET status = 'LOST' WHERE id = ?", sent))
                .as("the status check").isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the outbox write joins the service's JPA transaction: the event and the action roll back together")
    void outboxJoinsJpaTransaction() {
        final AuditOutbox outbox = configuredOutbox();
        final UUID eventId = UUID.randomUUID();
        final TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        transaction.executeWithoutResult(status -> {
            auditEventRepository.saveAndFlush(event(null, 50));
            outbox.enqueue(eventId, "acme", "INCIDENT_CREATED", "{}");
            status.setRollbackOnly();
        });

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM incident_audit_outbox WHERE id = ?",
                Integer.class, eventId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_events WHERE kafka_offset = 50",
                Integer.class)).isZero();
    }
}
