package com.incidentplatform.shared.pause;

import com.incidentplatform.shared.security.TenantStatusProvider;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.core.LockProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/**
 * The pause of suspended tenants' background work (backlog #0-82, step 2b) in a
 * service that sets {@code tenant-pause.table}: notification-, escalation- and
 * postmortem-service, whose schedulers act for a tenant (send, escalate, call
 * Gemini). The service provides the {@link PausableWork} bean, the
 * {@link LockProvider} (its ShedLock configuration), the {@link JdbcTemplate},
 * the transaction manager and scheduling; {@code shared}'s security
 * configuration provides the {@link TenantStatusProvider}.
 *
 * <p>Like the audit outbox's configuration, nothing here is conditional on a
 * class: a service that sets the property without a database, ShedLock or a
 * {@code PausableWork} fails at startup rather than running without the pause.
 */
@Configuration
@ConditionalOnProperty(prefix = "tenant-pause", name = "table")
@EnableConfigurationProperties(PausedTenantsProperties.class)
public class PausedTenantsConfiguration {

    @Bean
    public PausedTenants pausedTenants(JdbcTemplate jdbcTemplate, PausedTenantsProperties properties) {
        return new PausedTenants(jdbcTemplate, properties.table());
    }

    @Bean
    public PausedTenantsSync pausedTenantsSync(PausedTenants pausedTenants,
                                               PausableWork work,
                                               TenantStatusProvider tenantStatusProvider,
                                               PlatformTransactionManager transactionManager,
                                               LockProvider lockProvider,
                                               MeterRegistry meterRegistry,
                                               PausedTenantsProperties properties) {
        // The schedulers' queries name their paused table literally; the sync
        // writes the configured one. They must be the same table (found in review).
        if (!properties.table().equals(work.pausedTable())) {
            throw new IllegalStateException("tenant-pause.table is " + properties.table()
                    + " but this service's scheduler queries read " + work.pausedTable()
                    + ": the sync would pause tenants nobody reads");
        }
        return new PausedTenantsSync(pausedTenants, work, tenantStatusProvider, transactionManager, lockProvider,
                meterRegistry, properties, Clock.systemUTC());
    }

    @Bean
    public TenantWorkGuard tenantWorkGuard(TenantStatusProvider tenantStatusProvider, MeterRegistry meterRegistry) {
        return new TenantWorkGuard(tenantStatusProvider, meterRegistry);
    }
}
