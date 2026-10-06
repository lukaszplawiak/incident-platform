package com.incidentplatform.postmortem.repository;

import com.incidentplatform.postmortem.config.PostmortemProperties;
import com.incidentplatform.postmortem.domain.Postmortem;
import com.incidentplatform.postmortem.scheduler.PostmortemPausableWork;
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
import org.springframework.context.annotation.Bean;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * The pause of a suspended tenant's postmortems on a real Postgres (backlog
 * #0-82, step 2b): V7's table and both scheduler queries leaving a paused
 * tenant out, so no Gemini call is made for it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = PostmortemPauseIntegrationTest.JpaSliceConfig.class)
@Testcontainers
@DisplayName("postmortem-service tenant pause (Postgres, backlog #0-82)")
class PostmortemPauseIntegrationTest {

    private static final int MAX_RETRIES = 3;

    @Configuration
    @Import(PostmortemPausableWork.class)
    @EntityScan("com.incidentplatform.postmortem.domain")
    @EnableJpaRepositories("com.incidentplatform.postmortem.repository")
    static class JpaSliceConfig {
        @Bean
        PostmortemProperties postmortemProperties() {
            return new PostmortemProperties(MAX_RETRIES, Duration.ofMinutes(2), 10, 20);
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PostmortemRepository postmortemRepository;
    @Autowired private PostmortemPausableWork work;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Environment environment;

    @AfterEach
    void cleanCommitted() {
        jdbcTemplate.update("DELETE FROM postmortems WHERE tenant_id LIKE 'e2e-%'");
        jdbcTemplate.update("DELETE FROM postmortem_paused_tenants WHERE tenant_id LIKE 'e2e-%'");
    }

    private String configuredTable() {
        final String table = environment.getRequiredProperty("tenant-pause.table");
        assertThat(table).isEqualTo("postmortem_paused_tenants");
        return table;
    }

    private UUID postmortem(String tenantId, String status, int retries) {
        final UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO postmortems (id, incident_id, tenant_id, incident_title, incident_severity,
                    incident_opened_at, incident_resolved_at, duration_minutes, status, created_at, updated_at,
                    retry_count, version)
                VALUES (?, ?, ?, 'Disk full', 'HIGH', now() - interval '1 hour', now() - interval '30 minutes',
                    30, ?, now() - interval '10 minutes', now(), ?, 0)""",
                id, UUID.randomUUID(), tenantId, status, retries);
        return id;
    }

    private void paused(String tenantId) {
        jdbcTemplate.update("INSERT INTO postmortem_paused_tenants (tenant_id, access, paused_at) "
                + "VALUES (?, 'READ_ONLY', now())", tenantId);
    }

    @Test
    @DisplayName("both scheduler queries leave out a paused tenant's postmortems, and only those")
    void queriesExcludePaused() {
        configuredTable();
        final UUID pausedGenerating = postmortem("acme", "GENERATING", 0);
        final UUID pausedFailed = postmortem("acme", "FAILED", 1);
        final UUID activeGenerating = postmortem("globex", "GENERATING", 0);
        final UUID activeFailed = postmortem("globex", "FAILED", 1);
        paused("acme");

        assertThat(postmortemRepository.findStuckGenerating(Instant.now(), PageRequest.of(0, 10)))
                .extracting(Postmortem::getId).containsExactly(activeGenerating).doesNotContain(pausedGenerating);
        assertThat(postmortemRepository.findFailedWithRemainingRetries(MAX_RETRIES, PageRequest.of(0, 10)))
                .extracting(Postmortem::getId).containsExactly(activeFailed).doesNotContain(pausedFailed);
    }

    @Test
    @DisplayName("the pause's candidates: tenants with a postmortem to generate or retry, once")
    void candidates() {
        postmortem("acme", "GENERATING", 0);
        postmortem("acme", "FAILED", 1);
        postmortem("globex", "FAILED", MAX_RETRIES);
        postmortem("initech", "DRAFT", 0);

        assertThat(work.tenantsWithPendingWork()).containsExactly("acme");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("sync end to end: held while suspended; on resumption both a GENERATING and a FAILED postmortem "
            + "are picked up again, no retry spent meanwhile (review)")
    void syncEndToEnd() {
        final String table = configuredTable();
        final UUID id = postmortem("e2e-acme", "GENERATING", 0);
        final UUID failed = postmortem("e2e-acme", "FAILED", 1);
        final AtomicReference<TenantAccess> access = new AtomicReference<>(TenantAccess.READ_ONLY);
        final LockProvider lockProvider = mock(LockProvider.class);
        given(lockProvider.lock(any())).willReturn(Optional.of(() -> { }));
        final PausedTenantsSync sync = new PausedTenantsSync(new PausedTenants(jdbcTemplate, table), work,
                tenantId -> access.get(), transactionManager, lockProvider, new SimpleMeterRegistry(),
                new PausedTenantsProperties(table, null), Clock.systemUTC());

        sync.sync();
        assertThat(postmortemRepository.findStuckGenerating(Instant.now(), PageRequest.of(0, 50)))
                .extracting(Postmortem::getId).doesNotContain(id);
        assertThat(postmortemRepository.findFailedWithRemainingRetries(MAX_RETRIES, PageRequest.of(0, 50)))
                .extracting(Postmortem::getId).doesNotContain(failed);

        access.set(TenantAccess.FULL);
        sync.sync();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM postmortem_paused_tenants "
                + "WHERE tenant_id = 'e2e-acme'", Integer.class)).isZero();
        assertThat(postmortemRepository.findStuckGenerating(Instant.now(), PageRequest.of(0, 50)))
                .extracting(Postmortem::getId).contains(id);
        assertThat(postmortemRepository.findFailedWithRemainingRetries(MAX_RETRIES, PageRequest.of(0, 50)))
                .extracting(Postmortem::getId).contains(failed);
        assertThat(jdbcTemplate.queryForObject("SELECT retry_count FROM postmortems WHERE id = ?", Integer.class,
                failed)).as("no retry spent while paused").isEqualTo(1);
    }
}
