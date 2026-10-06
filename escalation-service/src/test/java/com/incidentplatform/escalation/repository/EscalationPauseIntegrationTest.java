package com.incidentplatform.escalation.repository;

import com.incidentplatform.escalation.domain.EscalationTask;
import com.incidentplatform.escalation.scheduler.EscalationPausableWork;
import com.incidentplatform.shared.pause.PausedTenants;
import com.incidentplatform.shared.pause.PausedTenantsProperties;
import com.incidentplatform.shared.pause.PausedTenantsSync;
import com.incidentplatform.shared.security.TenantAccess;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * The pause of a suspended tenant's escalations on a real Postgres (backlog
 * #0-82, step 2b): V9's table, the scheduler's query that leaves a paused
 * tenant out, and the resumption that stops the timers for the length of the
 * pause. Inside a test's transaction {@code now()} is one instant, so the
 * moved timers are checked to the second.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = EscalationPauseIntegrationTest.JpaSliceConfig.class)
@Testcontainers
@DisplayName("escalation-service tenant pause (Postgres, backlog #0-82)")
class EscalationPauseIntegrationTest {

    @Configuration
    @Import(EscalationPausableWork.class)
    @EntityScan("com.incidentplatform.escalation.domain")
    @EnableJpaRepositories("com.incidentplatform.escalation.repository")
    static class JpaSliceConfig {
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EscalationTaskRepository taskRepository;
    @Autowired private EscalationPausableWork work;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Environment environment;

    @AfterEach
    void cleanCommitted() {
        // The end-to-end test commits; the others roll back with their transaction.
        jdbcTemplate.update("DELETE FROM escalation_tasks WHERE tenant_id LIKE 'e2e-%'");
        jdbcTemplate.update("DELETE FROM escalation_paused_tenants WHERE tenant_id LIKE 'e2e-%'");
    }

    /** The table the service's application.yml names: a typo there would pause nothing. */
    private String configuredTable() {
        final String table = environment.getRequiredProperty("tenant-pause.table");
        assertThat(table).isEqualTo("escalation_paused_tenants");
        return table;
    }

    /**
     * A task whose timer started {@code startedMinutesAgo} and comes due in
     * {@code dueInMinutes} (negative: overdue), by the database's clock.
     */
    private UUID task(String tenantId, String status, int startedMinutesAgo, int dueInMinutes) {
        final UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO escalation_tasks (id, incident_id, tenant_id, incident_opened_at,
                    scheduled_escalation_at, status, severity, title, created_at, updated_at,
                    escalation_level, version, retry_count)
                VALUES (?, ?, ?, now() - make_interval(mins => ?), now() + make_interval(mins => ?),
                    ?, 'HIGH', 'Disk full', now(), now(), 1, 0, 0)""",
                id, UUID.randomUUID(), tenantId, startedMinutesAgo, dueInMinutes, status);
        return id;
    }

    private void pausedMinutesAgo(String tenantId, int minutes) {
        jdbcTemplate.update("INSERT INTO escalation_paused_tenants (tenant_id, access, paused_at) "
                + "VALUES (?, 'NONE', now() - make_interval(mins => ?))", tenantId, minutes);
    }

    /** Seconds from now() to the task's due time. */
    private long dueInSeconds(UUID taskId) {
        return jdbcTemplate.queryForObject("SELECT EXTRACT(EPOCH FROM scheduled_escalation_at - now())::bigint "
                + "FROM escalation_tasks WHERE id = ?", Long.class, taskId);
    }

    private long version(UUID taskId) {
        return jdbcTemplate.queryForObject("SELECT version FROM escalation_tasks WHERE id = ?", Long.class, taskId);
    }

    @Test
    @DisplayName("the scheduler's query leaves out a paused tenant's due tasks, and only those")
    void dueQueryExcludesPaused() {
        configuredTable();
        final UUID paused = task("acme", "PENDING", 30, -5);
        final UUID active = task("globex", "PENDING", 30, -1);
        pausedMinutesAgo("acme", 10);

        assertThat(taskRepository.findDueForEscalation(Instant.now().plusSeconds(60), PageRequest.of(0, 10)))
                .extracting(EscalationTask::getId).containsExactly(active).doesNotContain(paused);
    }

    @Test
    @DisplayName("the pause's candidates: every tenant with a PENDING task, due or not, once")
    void candidates() {
        task("acme", "PENDING", 1, 10);
        task("acme", "PENDING", 1, -1);
        task("globex", "ESCALATED", 1, -1);

        assertThat(work.tenantsWithPendingWork()).containsExactly("acme");
    }

    @Test
    @DisplayName("on resumption a timer gets the time it had left: running at the pause, started during it, "
            + "or already due before it")
    void resumptionStopsTimersForThePause() {
        // Paused 10 minutes ago.
        pausedMinutesAgo("acme", 10);
        final Instant pausedAt = jdbcTemplate.queryForObject(
                "SELECT paused_at FROM escalation_paused_tenants WHERE tenant_id = 'acme'", Timestamp.class)
                .toInstant();
        // Started 15 min ago, due in 5: had 15 min left at the pause -> due in 15.
        final UUID running = task("acme", "PENDING", 15, 5);
        // Started 5 min ago (during the pause), due in 10: full 15-min timeout from now.
        final UUID startedDuring = task("acme", "PENDING", 5, 10);
        // Due 20 min ago, before the pause: late already, left as it is.
        final UUID lateBefore = task("acme", "PENDING", 60, -20);
        final UUID escalated = task("acme", "ESCALATED", 15, 5);
        final UUID otherTenant = task("globex", "PENDING", 15, 5);

        work.onResume("acme", pausedAt);

        assertThat(dueInSeconds(running)).isEqualTo(15 * 60);
        assertThat(dueInSeconds(startedDuring)).isEqualTo(15 * 60);
        assertThat(dueInSeconds(lateBefore)).isEqualTo(-20 * 60);
        assertThat(dueInSeconds(escalated)).as("not pending").isEqualTo(5 * 60);
        assertThat(dueInSeconds(otherTenant)).as("another tenant").isEqualTo(5 * 60);
        assertThat(version(running)).as("a scheduler holding the old task loses its write").isEqualTo(1);
        assertThat(version(lateBefore)).isZero();
    }

    @Test
    @DisplayName("found in review: a task that came due after the suspension but before a late sync paused "
            + "the tenant (held by the guard meanwhile) is moved on too, by the time it had left at the suspension")
    void taskDueBeforeALateSyncMoved() {
        // Suspended 10 min ago (auth-service's suspended_at); the sync, late, paused it only just now.
        final Instant suspendedAt = jdbcTemplate.queryForObject("SELECT now() - interval '10 minutes'",
                Timestamp.class).toInstant();
        jdbcTemplate.update("INSERT INTO escalation_paused_tenants (tenant_id, access, paused_at) "
                + "VALUES ('acme', 'NONE', ?)", Timestamp.from(suspendedAt));
        // Started 15 min ago, came due 5 min ago, so 5 min after the suspension: 5 min left at it.
        final UUID heldByGuard = task("acme", "PENDING", 15, -5);

        work.onResume("acme", suspendedAt);

        assertThat(dueInSeconds(heldByGuard)).as("the 5 min it had left at the suspension").isEqualTo(5 * 60);
    }

    @Test
    @DisplayName("review: a pause start after now() (clock trouble) never pulls a timer earlier")
    void neverMovesATimerEarlier() {
        // Due in two hours, so it is after the (future) pause start and selected by the UPDATE.
        final UUID running = task("acme", "PENDING", 1, 120);

        work.onResume("acme", jdbcTemplate.queryForObject("SELECT now() + interval '1 hour'", Timestamp.class)
                .toInstant());

        assertThat(dueInSeconds(running)).as("moved by nothing rather than an hour back").isEqualTo(120 * 60);
        assertThat(version(running)).as("selected, so the guard was what held it").isEqualTo(1);
    }

    /**
     * The whole path, committed: the sync pauses the tenant on auth-service's
     * answer, the query stops returning its task, and the resumption moves the
     * timer on and deletes the row in the service's JPA transaction.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("sync end to end: paused on a suspension, released with its timer moved on when resumed")
    void syncEndToEnd() {
        final String table = configuredTable();
        final UUID taskId = task("e2e-acme", "PENDING", 1, 14);
        final AtomicReference<TenantAccess> access = new AtomicReference<>(TenantAccess.READ_ONLY);
        final LockProvider lockProvider = mock(LockProvider.class);
        given(lockProvider.lock(any())).willReturn(Optional.of(() -> { }));
        final PausedTenantsSync sync = new PausedTenantsSync(new PausedTenants(jdbcTemplate, table), work,
                tenantId -> access.get(), transactionManager, lockProvider, new SimpleMeterRegistry(),
                new PausedTenantsProperties(table, null), Clock.systemUTC());

        sync.sync();
        assertThat(jdbcTemplate.queryForObject("SELECT access FROM escalation_paused_tenants "
                + "WHERE tenant_id = 'e2e-acme'", String.class)).isEqualTo("READ_ONLY");
        jdbcTemplate.update("UPDATE escalation_tasks SET scheduled_escalation_at = now() - interval '1 second' "
                + "WHERE id = ?", taskId);
        assertThat(taskRepository.findDueForEscalation(Instant.now().plusSeconds(60), PageRequest.of(0, 10)))
                .extracting(EscalationTask::getId).doesNotContain(taskId);
        // As if the pause had begun an hour ago, with the timer already running.
        jdbcTemplate.update("UPDATE escalation_paused_tenants SET paused_at = now() - interval '1 hour' "
                + "WHERE tenant_id = 'e2e-acme'");
        jdbcTemplate.update("UPDATE escalation_tasks SET incident_opened_at = now() - interval '2 hours' "
                + "WHERE id = ?", taskId);

        access.set(TenantAccess.FULL);
        sync.sync();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM escalation_paused_tenants "
                + "WHERE tenant_id = 'e2e-acme'", Integer.class)).isZero();
        assertThat(dueInSeconds(taskId)).as("moved on by about an hour").isBetween(3590L, 3600L);
        assertThat(version(taskId)).isEqualTo(1);
    }
}
