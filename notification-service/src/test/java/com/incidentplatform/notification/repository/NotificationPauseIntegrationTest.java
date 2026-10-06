package com.incidentplatform.notification.repository;

import com.incidentplatform.notification.domain.NotificationQueueEntry;
import com.incidentplatform.notification.scheduler.NotificationPausableWork;
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
 * The pause of a suspended tenant's notifications on a real Postgres (backlog
 * #0-82, step 2b): V10's table, the scheduler's query that leaves a paused
 * tenant out, and the resumption that releases its entries with their lookup
 * retry windows started again.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = NotificationPauseIntegrationTest.JpaSliceConfig.class)
@Testcontainers
@DisplayName("notification-service tenant pause (Postgres, backlog #0-82)")
class NotificationPauseIntegrationTest {

    @Configuration
    @Import(NotificationPausableWork.class)
    @EntityScan("com.incidentplatform.notification.domain")
    @EnableJpaRepositories("com.incidentplatform.notification.repository")
    static class JpaSliceConfig {
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private NotificationQueueRepository queueRepository;
    @Autowired private NotificationPausableWork work;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Environment environment;

    @AfterEach
    void cleanCommitted() {
        jdbcTemplate.update("DELETE FROM notification_queue WHERE tenant_id LIKE 'e2e-%'");
        jdbcTemplate.update("DELETE FROM notification_paused_tenants WHERE tenant_id LIKE 'e2e-%'");
    }

    /** The table the service's application.yml names: a typo there would pause nothing. */
    private String configuredTable() {
        final String table = environment.getRequiredProperty("tenant-pause.table");
        assertThat(table).isEqualTo("notification_paused_tenants");
        return table;
    }

    /** A queue entry written {@code minutesAgo}, its first lookup failure set when {@code failedLookup}. */
    private UUID entry(String tenantId, String status, int minutesAgo, boolean failedLookup) {
        final UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO notification_queue (id, incident_id, tenant_id, event_type, severity, title, status,
                    created_at, escalation_level, first_lookup_failure_at)
                VALUES (?, ?, ?, 'IncidentOpenedEvent', 'CRITICAL', 'High CPU', ?,
                    now() - make_interval(mins => ?), 0, CASE WHEN ? THEN now() ELSE NULL END)""",
                id, UUID.randomUUID(), tenantId, status, minutesAgo, failedLookup);
        return id;
    }

    private void paused(String tenantId) {
        jdbcTemplate.update("INSERT INTO notification_paused_tenants (tenant_id, access, paused_at) "
                + "VALUES (?, 'NONE', now())", tenantId);
    }

    private boolean hasLookupFailure(UUID entryId) {
        return jdbcTemplate.queryForObject("SELECT first_lookup_failure_at IS NOT NULL FROM notification_queue "
                + "WHERE id = ?", Boolean.class, entryId);
    }

    @Test
    @DisplayName("the scheduler's query leaves out a paused tenant's entries, even the oldest, and only those")
    void pendingQueryExcludesPaused() {
        configuredTable();
        final UUID pausedOldest = entry("acme", "PENDING", 60, false);
        final UUID active = entry("globex", "PENDING", 5, false);
        paused("acme");

        assertThat(queueRepository.findPendingOlderThan(Instant.now(), PageRequest.of(0, 1)))
                .as("a batch of one is not filled by the paused tenant's older entry")
                .extracting(NotificationQueueEntry::getId).containsExactly(active).doesNotContain(pausedOldest);
    }

    @Test
    @DisplayName("the pause's candidates: every tenant with a PENDING entry, once")
    void candidates() {
        entry("acme", "PENDING", 1, false);
        entry("acme", "PENDING", 2, false);
        entry("globex", "SENT", 1, false);

        assertThat(work.tenantsWithPendingWork()).containsExactly("acme");
    }

    @Test
    @DisplayName("on resumption a pending entry's lookup retry window starts again; nothing else changes")
    void resumptionRestartsLookupWindows() {
        final UUID failed = entry("acme", "PENDING", 30, true);
        final UUID sentFailed = entry("acme", "SENT", 30, true);
        final UUID otherTenant = entry("globex", "PENDING", 30, true);

        work.onResume("acme", Instant.now());

        assertThat(hasLookupFailure(failed)).isFalse();
        assertThat(hasLookupFailure(sentFailed)).as("not pending").isTrue();
        assertThat(hasLookupFailure(otherTenant)).as("another tenant").isTrue();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("sync end to end: held while suspended, released when resumed, in the JPA transaction")
    void syncEndToEnd() {
        final String table = configuredTable();
        final UUID entryId = entry("e2e-acme", "PENDING", 10, true);
        final AtomicReference<TenantAccess> access = new AtomicReference<>(TenantAccess.NONE);
        final LockProvider lockProvider = mock(LockProvider.class);
        given(lockProvider.lock(any())).willReturn(Optional.of(() -> { }));
        final PausedTenantsSync sync = new PausedTenantsSync(new PausedTenants(jdbcTemplate, table), work,
                tenantId -> access.get(), transactionManager, lockProvider, new SimpleMeterRegistry(),
                new PausedTenantsProperties(table, null), Clock.systemUTC());

        sync.sync();
        assertThat(queueRepository.findPendingOlderThan(Instant.now(), PageRequest.of(0, 50)))
                .extracting(NotificationQueueEntry::getId).doesNotContain(entryId);

        access.set(TenantAccess.FULL);
        sync.sync();

        assertThat(queueRepository.findPendingOlderThan(Instant.now(), PageRequest.of(0, 50)))
                .extracting(NotificationQueueEntry::getId).contains(entryId);
        assertThat(hasLookupFailure(entryId)).isFalse();
    }
}
