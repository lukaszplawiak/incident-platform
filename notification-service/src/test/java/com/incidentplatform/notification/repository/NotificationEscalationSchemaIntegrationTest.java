package com.incidentplatform.notification.repository;

import com.incidentplatform.notification.domain.NotificationLog;
import com.incidentplatform.notification.domain.NotificationQueueEntry;
import com.incidentplatform.shared.domain.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-Postgres tests for the escalation-level-aware idempotency keys added
 * by the {@code V5} migration.
 *
 * <h2>What a mocked repository cannot prove</h2>
 * <ul>
 *   <li>{@code V5} applies cleanly on top of {@code V1}–{@code V4}, and
 *       {@code ddl-auto: validate} accepts the new
 *       {@code escalation_level} / {@code escalate_to} columns on the
 *       entities. A slice test that fails to start would fail every test
 *       here.</li>
 *   <li>{@code uq_notification_queue_incident_tenant_event_level} really is
 *       keyed on {@code (incident_id, tenant_id, event_type,
 *       escalation_level)}: two levels of the same escalation coexist, a
 *       redelivered level is rejected, and another tenant's row with the
 *       same incident id neither blocks nor satisfies the check.</li>
 *   <li>The {@code CHECK (escalation_level >= 0)} constraints hold.</li>
 *   <li>The derived {@code exists...} queries the service relies on
 *       distinguish levels.</li>
 * </ul>
 *
 * <p>Regression context: idempotency used to be keyed on
 * {@code (incident_id, event_type)}, so the level-2 (MANAGER) escalation
 * notification was discarded as a duplicate of level 1.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes =
        NotificationEscalationSchemaIntegrationTest.JpaSliceConfig.class)
@Testcontainers
@DisplayName("notification escalation-level idempotency — real Postgres")
class NotificationEscalationSchemaIntegrationTest {

    /**
     * Minimal JPA configuration instead of the application class:
     * {@code NotificationServiceApplication} carries an explicit
     * {@code @ComponentScan}, which switches off {@code @DataJpaTest}'s
     * slice filtering and would load every controller, client and Kafka
     * bean — none of which this test needs. Flyway and the DataSource still
     * come from the {@code @DataJpaTest} auto-configuration, so the real
     * {@code db/migration} scripts run against the container.
     */
    @Configuration
    @EntityScan("com.incidentplatform.notification.domain")
    @EnableJpaRepositories("com.incidentplatform.notification.repository")
    static class JpaSliceConfig {
    }

    // postgres:16-alpine — same image as docker/docker-compose.yml and the
    // other Testcontainers-based tests in this codebase.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine").withReuse(true);

    private static final String TENANT_ID = "test-tenant";
    private static final String ESCALATED = "IncidentEscalatedEvent";
    private static final String OPENED = "IncidentOpenedEvent";

    @Autowired
    private NotificationQueueRepository queueRepository;

    @Autowired
    private NotificationLogRepository logRepository;

    private NotificationQueueEntry entry(UUID incidentId, String eventType,
                                         int level, UUID escalateTo) {
        return NotificationQueueEntry.pending(
                incidentId, TENANT_ID, eventType, Severity.CRITICAL,
                "High CPU", level, escalateTo);
    }

    @Test
    @DisplayName("queue: level 1 and level 2 of the same escalation can both be stored")
    void queueAllowsBothEscalationLevels() {
        final UUID incidentId = UUID.randomUUID();

        queueRepository.saveAndFlush(entry(incidentId, ESCALATED, 1, null));
        queueRepository.saveAndFlush(entry(incidentId, ESCALATED, 2, null));

        assertThat(queueRepository.existsByIncidentIdAndTenantIdAndEventTypeAndEscalationLevel(
                incidentId, TENANT_ID, ESCALATED, 1)).isTrue();
        assertThat(queueRepository.existsByIncidentIdAndTenantIdAndEventTypeAndEscalationLevel(
                incidentId, TENANT_ID, ESCALATED, 2)).isTrue();
        assertThat(queueRepository.existsByIncidentIdAndTenantIdAndEventTypeAndEscalationLevel(
                incidentId, TENANT_ID, ESCALATED, 3)).isFalse();
    }

    @Test
    @DisplayName("queue: the same level of the same escalation is still unique")
    void queueRejectsDuplicateLevel() {
        final UUID incidentId = UUID.randomUUID();
        queueRepository.saveAndFlush(entry(incidentId, ESCALATED, 1, null));

        assertThatThrownBy(() -> queueRepository.saveAndFlush(
                entry(incidentId, ESCALATED, 1, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("queue: non-escalation events (level 0) stay unique per incident and type")
    void queueKeepsNonEscalationEventsUnique() {
        final UUID incidentId = UUID.randomUUID();
        queueRepository.saveAndFlush(entry(incidentId, OPENED, 0, null));

        assertThatThrownBy(() -> queueRepository.saveAndFlush(
                entry(incidentId, OPENED, 0, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("queue: idempotency is tenant-scoped — another tenant's row with the same incident id does not block or satisfy it")
    void queueIdempotencyIsTenantScoped() {
        final UUID incidentId = UUID.randomUUID();
        queueRepository.saveAndFlush(NotificationQueueEntry.pending(
                incidentId, "other-tenant", ESCALATED, Severity.CRITICAL,
                "High CPU", 1, null));

        // The other tenant's row must not count as "already queued" for us ...
        assertThat(queueRepository
                .existsByIncidentIdAndTenantIdAndEventTypeAndEscalationLevel(
                        incidentId, TENANT_ID, ESCALATED, 1)).isFalse();
        // ... and must not prevent us from queueing our own.
        queueRepository.saveAndFlush(entry(incidentId, ESCALATED, 1, null));
        assertThat(queueRepository
                .existsByIncidentIdAndTenantIdAndEventTypeAndEscalationLevel(
                        incidentId, TENANT_ID, ESCALATED, 1)).isTrue();
    }

    @Test
    @DisplayName("queue: a negative escalation level is rejected by the CHECK constraint")
    void queueRejectsNegativeEscalationLevel() {
        assertThatThrownBy(() -> queueRepository.saveAndFlush(
                entry(UUID.randomUUID(), ESCALATED, -1, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("queue: escalation target and level round-trip")
    void queuePersistsEscalationContext() {
        final UUID incidentId = UUID.randomUUID();
        final UUID escalateTo = UUID.randomUUID();

        final NotificationQueueEntry saved = queueRepository.saveAndFlush(
                entry(incidentId, ESCALATED, 2, escalateTo));
        queueRepository.flush();

        final NotificationQueueEntry loaded =
                queueRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getEscalationLevel()).isEqualTo(2);
        assertThat(loaded.getEscalateTo()).isEqualTo(escalateTo);
    }

    @Test
    @DisplayName("log: a level-2 send is not treated as already sent by level 1")
    void logDistinguishesEscalationLevels() {
        final UUID incidentId = UUID.randomUUID();
        logRepository.saveAndFlush(NotificationLog.sent(
                incidentId, TENANT_ID, ESCALATED, 1, "EMAIL",
                "secondary@example.com", "[ESCALATED] High CPU", "body"));

        assertThat(logRepository
                .existsByIncidentIdAndTenantIdAndEventTypeAndEscalationLevelAndChannel(
                        incidentId, TENANT_ID, ESCALATED, 1, "EMAIL")).isTrue();
        assertThat(logRepository
                .existsByIncidentIdAndTenantIdAndEventTypeAndEscalationLevelAndChannel(
                        incidentId, TENANT_ID, ESCALATED, 2, "EMAIL")).isFalse();
        assertThat(logRepository
                .existsByIncidentIdAndTenantIdAndEventTypeAndEscalationLevelAndChannel(
                        incidentId, TENANT_ID, ESCALATED, 1, "SLACK")).isFalse();
    }

    @Test
    @DisplayName("log: idempotency is tenant-scoped")
    void logIdempotencyIsTenantScoped() {
        final UUID incidentId = UUID.randomUUID();
        logRepository.saveAndFlush(NotificationLog.sent(
                incidentId, "other-tenant", ESCALATED, 1, "EMAIL",
                "someone@example.com", "[ESCALATED] High CPU", "body"));

        assertThat(logRepository
                .existsByIncidentIdAndTenantIdAndEventTypeAndEscalationLevelAndChannel(
                        incidentId, TENANT_ID, ESCALATED, 1, "EMAIL")).isFalse();
    }

    @Test
    @DisplayName("log: a negative escalation level is rejected by the CHECK constraint")
    void logRejectsNegativeEscalationLevel() {
        assertThatThrownBy(() -> logRepository.saveAndFlush(NotificationLog.sent(
                UUID.randomUUID(), TENANT_ID, ESCALATED, -1, "EMAIL",
                "someone@example.com", "subject", "body")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
