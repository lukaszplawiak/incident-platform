package com.incidentplatform.shared.pause;

import com.incidentplatform.shared.security.TenantAccess;
import com.incidentplatform.shared.security.TenantStatusProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The pause's wiring (backlog #0-82, step 2b): {@code tenant-pause.table} turns
 * it on; a service that sets it without saying what its work is does not
 * start, rather than run without the pause.
 */
@DisplayName("PausedTenantsConfiguration")
class PausedTenantsConfigurationTest {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(LockProvider.class, () -> mock(LockProvider.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(TenantStatusProvider.class, () -> tenantId -> TenantAccess.FULL)
            .withUserConfiguration(PausedTenantsConfiguration.class);

    @Test
    @DisplayName("with tenant-pause.table and the service's work: the table, the sync and the guard exist")
    void onWithTable() {
        contexts.withBean(PausableWork.class, () -> work("escalation_paused_tenants"))
                .withPropertyValues("tenant-pause.table=escalation_paused_tenants",
                        "tenant-pause.processing-budget=30s")
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(PausedTenants.class)
                            .hasSingleBean(PausedTenantsSync.class)
                            .hasSingleBean(TenantWorkGuard.class);
                    assertThat(context.getBean(PausedTenants.class).table()).isEqualTo("escalation_paused_tenants");
                    assertThat(context.getBean(PausedTenantsProperties.class).processingBudget())
                            .isEqualTo(Duration.ofSeconds(30));
                });
    }

    @Test
    @DisplayName("review: tenant-pause.table that is not the table the service's queries read stops the startup")
    void tableMismatchFailsStartup() {
        contexts.withBean(PausableWork.class, () -> work("escalation_paused_tenants"))
                .withPropertyValues("tenant-pause.table=escalation_paused_tenants_v2")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("escalation_paused_tenants_v2"));
    }

    @Test
    @DisplayName("without it: nothing (ingestion-, incident-, oncall- and auth-service)")
    void offWithoutTable() {
        contexts.run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(PausedTenants.class).doesNotHaveBean(PausedTenantsSync.class)
                .doesNotHaveBean(TenantWorkGuard.class));
    }

    @Test
    @DisplayName("a service that sets the table but provides no PausableWork does not start")
    void tableWithoutWorkFailsStartup() {
        contexts.withPropertyValues("tenant-pause.table=escalation_paused_tenants")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("a table name that is not a plain identifier stops the startup")
    void badTableName() {
        contexts.withBean(PausableWork.class, () -> work("escalation_paused_tenants"))
                .withPropertyValues("tenant-pause.table=paused; drop table users")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("a processing budget that would outlive the sync's lock is refused")
    void budgetWithinLock() {
        assertThatThrownBy(() -> new PausedTenantsProperties("t", PausedTenantsSync.LOCK_AT_MOST_FOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PausedTenantsProperties("t", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new PausedTenantsProperties(null, null).processingBudget()).isEqualTo(Duration.ofSeconds(90));
        assertThat(new PausedTenantsProperties("t", PausedTenantsSync.LOCK_AT_MOST_FOR
                .minus(PausedTenantsSync.LOCK_MARGIN)).processingBudget())
                .isEqualTo(Duration.ofSeconds(105));
    }

    /** A service's work: nothing waiting, its queries reading {@code table}. */
    private static PausableWork work(String table) {
        return new PausableWork() {
            @Override
            public List<String> tenantsWithPendingWork() {
                return List.of();
            }

            @Override
            public String pausedTable() {
                return table;
            }
        };
    }
}
