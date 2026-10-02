package com.incidentplatform.postmortem.repository;

import com.incidentplatform.postmortem.domain.Postmortem;
import com.incidentplatform.postmortem.service.PostmortemPersistenceService;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditOutbox;
import com.incidentplatform.shared.domain.Severity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;

/**
 * postmortem-service's audit outbox on a real Postgres (backlog #0-84, second
 * step): Flyway runs every migration up to V5, the shared
 * {@link AuditOutbox} SQL fits the table, and its writes join the service's
 * JPA transaction, so an audit event commits or rolls back with the action
 * it records. A mocked publisher cannot show either.
 *
 * <p>{@link PostmortemPersistenceService} marks a postmortem and writes its
 * audit event in one transaction (found in review: the mechanism alone was
 * tested, not the service's own transaction). The publisher here is a mock
 * that writes to the real outbox (the real one is built only by
 * {@code AuditOutboxConfiguration}, which needs Kafka and ShedLock), so a
 * method that lost its transaction, or a tenant other than the
 * postmortem's, would show.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = AuditOutboxPersistenceIntegrationTest.JpaSliceConfig.class)
@Testcontainers
@DisplayName("postmortem-service audit outbox (Postgres, backlog #0-84)")
class AuditOutboxPersistenceIntegrationTest {

    /**
     * Minimal JPA configuration instead of the application class, whose
     * explicit {@code @ComponentScan} would load every bean of the service.
     * Flyway and the DataSource still come from {@code @DataJpaTest}, so the
     * real {@code db/migration} scripts run against the container.
     */
    @Configuration
    @Import(PostmortemPersistenceService.class)
    @EntityScan("com.incidentplatform.postmortem.domain")
    @EnableJpaRepositories("com.incidentplatform.postmortem.repository")
    static class JpaSliceConfig {
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;
    @Autowired private Environment environment;
    @Autowired private PostmortemPersistenceService persistenceService;
    @MockitoBean private AuditEventPublisher auditEventPublisher;

    /**
     * Some tests commit outbox rows (their own transactions, outside the test
     * one), and {@code outboxTableFitsSharedSql} asserts on the whole table:
     * emptied before each test so no result depends on test order (found in
     * review). Inside a test's transaction the delete is rolled back with it,
     * but hides the committed rows from that test all the same.
     */
    @BeforeEach
    void emptyOutbox() {
        jdbcTemplate.update("DELETE FROM postmortem_audit_outbox");
    }

    /**
     * The outbox on the table the service's application.yml names: a
     * hard-coded name would let a typo in the property pass every test and
     * fail every audited action at runtime.
     */
    private AuditOutbox configuredOutbox() {
        final String table = environment.getRequiredProperty("audit.outbox.table");
        assertThat(table).isEqualTo("postmortem_audit_outbox");
        return new AuditOutbox(jdbcTemplate, table);
    }

    private Postmortem action() {
        return Postmortem.createGenerating(UUID.randomUUID(), "acme", "Disk full", Severity.HIGH,
                Instant.now().minusSeconds(600), Instant.now(), 10);
    }

    @Test
    @DisplayName("V5's table takes the shared outbox SQL: write, due, sent, failed, backlog")
    void outboxTableFitsSharedSql() {
        final AuditOutbox outbox = configuredOutbox();
        final UUID sent = UUID.randomUUID();
        final UUID failed = UUID.randomUUID();

        outbox.enqueue(sent, "acme", "POSTMORTEM_GENERATED", "{\"eventId\":\"" + sent + "\"}");
        outbox.enqueue(failed, "acme", "POSTMORTEM_GENERATED", "{}");

        assertThat(jdbcTemplate.queryForMap("SELECT status, attempts, tenant_id FROM postmortem_audit_outbox WHERE id = ?", sent))
                .containsEntry("status", "PENDING")
                .containsEntry("attempts", 0)
                .containsEntry("tenant_id", "acme");
        assertThat(outbox.anyDue()).isTrue();
        assertThat(outbox.due(10)).extracting(AuditOutbox.Pending::id).containsExactlyInAnyOrder(sent, failed);
        assertThat(outbox.markSent(List.of(sent))).isEqualTo(1);
        assertThat(outbox.markFailed(failed, Duration.ofSeconds(60), "down")).isTrue();
        assertThat(outbox.anyDue()).isFalse();
        assertThat(outbox.backlog().pending()).isEqualTo(1);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE postmortem_audit_outbox SET status = 'LOST' WHERE id = ?", sent))
                .as("the status check").isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the outbox write joins the service's JPA transaction: the event and the action roll back together")
    void outboxJoinsJpaTransaction() {
        final AuditOutbox outbox = configuredOutbox();
        final UUID eventId = UUID.randomUUID();
        final Postmortem action = action();
        final TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        transaction.executeWithoutResult(status -> {
            entityManager.persist(action);
            entityManager.flush();
            outbox.enqueue(eventId, "acme", "POSTMORTEM_GENERATED", "{}");
            status.setRollbackOnly();
        });

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM postmortem_audit_outbox WHERE id = ?",
                Integer.class, eventId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM postmortems WHERE incident_id = ?",
                Integer.class, action.getIncidentId())).isZero();

        transaction.executeWithoutResult(status -> {
            entityManager.persist(action());
            outbox.enqueue(eventId, "acme", "POSTMORTEM_GENERATED", "{}");
        });
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM postmortem_audit_outbox WHERE id = ?",
                Integer.class, eventId)).as("committed with the action").isEqualTo(1);
    }

    /** A GENERATING postmortem, committed, as the Kafka consumer leaves it. */
    private Postmortem committedGenerating(String tenantId) {
        final Postmortem postmortem = Postmortem.createGenerating(UUID.randomUUID(), tenantId, "Disk full",
                Severity.HIGH, Instant.now().minusSeconds(600), Instant.now(), 10);
        final TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> entityManager.persist(postmortem));
        return postmortem;
    }

    private String status(Postmortem postmortem) {
        return jdbcTemplate.queryForObject("SELECT status FROM postmortems WHERE id = ?",
                String.class, postmortem.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("markFailedAndPublish commits the FAILED mark and its event, under the postmortem's tenant")
    void failedMarkAndAuditCommitTogether() {
        final AuditOutbox outbox = configuredOutbox();
        willAnswer(invocation -> {
            outbox.enqueue(UUID.randomUUID(), invocation.getArgument(1), invocation.getArgument(2), "{}");
            return null;
        }).given(auditEventPublisher).publishIncident(any(), anyString(), anyString(), anyString(), anyString(), any());
        final Postmortem postmortem = committedGenerating("tenant-failed");

        persistenceService.markFailedAndPublish(postmortem.getId(), postmortem.getIncidentId(),
                "tenant-failed", "Gemini timeout");

        assertThat(status(postmortem)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForList(
                "SELECT tenant_id FROM postmortem_audit_outbox WHERE event_type = 'POSTMORTEM_FAILED' "
                        + "AND tenant_id = 'tenant-failed'", String.class))
                .as("one event, under the postmortem's own tenant").containsExactly("tenant-failed");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("a failed audit write rolls the FAILED mark back")
    void auditFailureRollsBackFailedMark() {
        willThrow(new IllegalArgumentException("unstorable")).given(auditEventPublisher)
                .publishIncident(any(), anyString(), anyString(), anyString(), anyString(), any());
        final Postmortem postmortem = committedGenerating("acme");

        assertThatThrownBy(() -> persistenceService.markFailedAndPublish(postmortem.getId(),
                postmortem.getIncidentId(), "acme", "Gemini timeout")).hasMessage("unstorable");

        assertThat(status(postmortem)).isEqualTo("GENERATING");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("a failed audit write rolls the PERMANENTLY_FAILED mark back as well (found in review)")
    void auditFailureRollsBackPermanentlyFailedMark() {
        willThrow(new IllegalArgumentException("unstorable")).given(auditEventPublisher)
                .publishIncident(any(), anyString(), anyString(), anyString(), anyString(), any());
        final Postmortem postmortem = committedGenerating("acme");

        assertThatThrownBy(() -> persistenceService.markPermanentlyFailedAndPublish(postmortem.getId(),
                postmortem.getIncidentId(), "acme", "Gemini timeout", 3)).hasMessage("unstorable");

        assertThat(status(postmortem)).isEqualTo("GENERATING");
    }
}
