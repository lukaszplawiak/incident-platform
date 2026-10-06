package com.incidentplatform.shared.pause;

import com.incidentplatform.shared.security.TenantAccess;
import com.incidentplatform.shared.security.TenantStatusProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The paused-tenants SQL on a real Postgres (backlog #0-82, step 2b): the upsert
 * that tells a new pause from a change of mode, the row lock of a resumption,
 * and a resumption that commits or rolls back with what the service does on it.
 * The table is the one each service's migration creates.
 */
@DisplayName("PausedTenants (Postgres)")
class PausedTenantsTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String TABLE = "test_paused_tenants";

    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactionManager;
    private static PausedTenants pausedTenants;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        final SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.postgresql.Driver(),
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        pausedTenants = new PausedTenants(jdbc, TABLE);
        // The shape every service's migration creates.
        jdbc.execute("""
                CREATE TABLE test_paused_tenants (
                    tenant_id VARCHAR(63) PRIMARY KEY
                        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$'),
                    access    VARCHAR(16) NOT NULL CHECK (access IN ('READ_ONLY', 'NONE')),
                    paused_at TIMESTAMPTZ NOT NULL
                )""");
        jdbc.execute("CREATE TABLE test_work (tenant_id VARCHAR(63) NOT NULL, due_at TIMESTAMPTZ NOT NULL)");
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM " + TABLE);
        jdbc.update("DELETE FROM test_work");
    }

    @Test
    @DisplayName("a new pause, the same pause again, and a change of mode that keeps when it began")
    void pauseUpsert() {
        assertThat(pausedTenants.pause("acme", TenantAccess.READ_ONLY, null)).isEqualTo(PausedTenants.Change.PAUSED);
        final Instant since = pausedTenants.all().get(0).pausedAt();

        assertThat(pausedTenants.pause("acme", TenantAccess.READ_ONLY, null)).isEqualTo(PausedTenants.Change.UNCHANGED);
        assertThat(pausedTenants.pause("acme", TenantAccess.NONE, null)).isEqualTo(PausedTenants.Change.MODE_CHANGED);

        assertThat(pausedTenants.all()).containsExactly(new PausedTenants.Paused("acme", TenantAccess.NONE, since));
        assertThat(pausedTenants.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("review: the pause begins at the suspension's time when auth-service gives it, and a later "
            + "answer does not move it")
    void pausedAtIsSuspensionTime() {
        final Instant suspendedAt = Instant.parse("2026-10-06T08:00:00Z");

        pausedTenants.pause("acme", TenantAccess.READ_ONLY, suspendedAt);
        pausedTenants.pause("acme", TenantAccess.NONE, Instant.parse("2026-10-06T09:00:00Z"));

        assertThat(pausedTenants.all()).containsExactly(
                new PausedTenants.Paused("acme", TenantAccess.NONE, suspendedAt));
    }

    @Test
    @DisplayName("full access is never a pause, and a tenant id is checked before any SQL")
    void refusesFullAndInvalidTenant() {
        assertThatThrownBy(() -> pausedTenants.pause("acme", TenantAccess.FULL, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pausedTenants.pause("ACME'; --", TenantAccess.NONE, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(pausedTenants.count()).isZero();
    }

    @Test
    @DisplayName("all() lists the longest paused first")
    void allOrdered() {
        jdbc.update("INSERT INTO " + TABLE + " VALUES ('newer', 'NONE', now()), "
                + "('older', 'READ_ONLY', now() - interval '1 hour')");

        assertThat(pausedTenants.all()).extracting(PausedTenants.Paused::tenantId).containsExactly("older", "newer");
    }

    @Test
    @DisplayName("lockForResume gives the start of the pause, or nothing for a tenant not paused")
    void lockForResume() {
        pausedTenants.pause("acme", TenantAccess.NONE, null);
        final TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        final Optional<Instant> since = transaction.execute(status -> pausedTenants.lockForResume("acme"));
        assertThat(since).isPresent();
        final Optional<Instant> none = transaction.execute(status -> pausedTenants.lockForResume("globex"));
        assertThat(none).isEmpty();
        assertThat(pausedTenants.delete("acme")).isTrue();
        assertThat(pausedTenants.delete("acme")).isFalse();
    }

    /**
     * The resumption's transaction on a real database: what the service does on
     * it (here: move a timer on) and the deletion of the row commit together, or
     * neither does and the tenant stays paused for the next sync.
     */
    @Test
    @DisplayName("a resumption commits the service's change with the deletion, and rolls both back on failure")
    void resumptionIsOneTransaction() {
        jdbc.update("INSERT INTO test_work VALUES ('acme', now())");
        final List<String> failOn = new ArrayList<>();
        final PausableWork work = new PausableWork() {
            @Override
            public List<String> tenantsWithPendingWork() {
                return jdbc.queryForList("SELECT DISTINCT tenant_id FROM test_work", String.class);
            }

            @Override
            public String pausedTable() {
                return TABLE;
            }

            @Override
            public void onResume(String tenantId, Instant pausedAt) {
                jdbc.update("UPDATE test_work SET due_at = due_at + interval '1 hour' WHERE tenant_id = ?",
                        tenantId);
                if (failOn.contains(tenantId)) {
                    throw new IllegalStateException("resume hook failed");
                }
            }
        };
        final TenantAccess[] access = {TenantAccess.NONE};
        final TenantStatusProvider provider = tenantId -> access[0];
        final PausedTenantsSync sync = new PausedTenantsSync(pausedTenants, work, provider, transactionManager,
                mock(LockProvider.class), new SimpleMeterRegistry(), new PausedTenantsProperties(TABLE, null), Clock.systemUTC());
        final Instant due = jdbc.queryForObject("SELECT due_at FROM test_work", java.sql.Timestamp.class)
                .toInstant();

        assertThat(sync.syncNow().paused()).isEqualTo(1);

        access[0] = TenantAccess.FULL;
        failOn.add("acme");
        assertThat(sync.syncNow().failed()).isEqualTo(1);
        assertThat(pausedTenants.count()).as("still paused").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT due_at FROM test_work", java.sql.Timestamp.class).toInstant())
                .as("the hook's change rolled back").isEqualTo(due);

        failOn.clear();
        assertThat(sync.syncNow().resumed()).isEqualTo(1);
        assertThat(pausedTenants.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT due_at FROM test_work", java.sql.Timestamp.class).toInstant())
                .isEqualTo(due.plusSeconds(3600));
    }

    @Test
    @DisplayName("a table name that is not a plain lower-case identifier, or too long for the lock name, is refused")
    void tableName() {
        assertThatThrownBy(() -> new PausedTenants(jdbc, "paused; drop table users"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PausedTenants(jdbc, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PausedTenants(jdbc, "a".repeat(PausedTenants.MAX_TABLE_NAME_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new PausedTenants(jdbc, "a".repeat(PausedTenants.MAX_TABLE_NAME_LENGTH)).table())
                .hasSize(PausedTenants.MAX_TABLE_NAME_LENGTH);
        assertThat(PausedTenantsSync.LOCK_PREFIX.length() + PausedTenants.MAX_TABLE_NAME_LENGTH)
                .as("fits shedlock.name VARCHAR(64)").isEqualTo(64);
    }
}
