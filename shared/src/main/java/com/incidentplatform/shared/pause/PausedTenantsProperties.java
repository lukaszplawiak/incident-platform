package com.incidentplatform.shared.pause;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings of a service's pause of suspended tenants' background work (backlog
 * #0-82, step 2b). Setting {@code table} turns it on; the sync's schedule is
 * {@code tenant-pause.sync-interval-ms} (default 10,000, the status cache's
 * TTL), read by its {@code @Scheduled} annotation.
 *
 * @param table            the service's own table, created by its Flyway
 *                         migrations ({@code <service>_paused_tenants})
 * @param processingBudget how long one sync may spend asking about tenants
 *                         before it leaves the rest for the next (default 90 s;
 *                         each lookup is bounded by the status client's
 *                         timeouts, about 3 s while auth-service is down); at
 *                         most the sync's lock time less a margin
 */
@ConfigurationProperties(prefix = "tenant-pause")
public record PausedTenantsProperties(String table, Duration processingBudget) {

    public PausedTenantsProperties {
        if (table != null) {
            // Interpolated into SQL and into the sync's lock name: checked at startup.
            PausedTenants.checkedTableName(table);
        }
        processingBudget = processingBudget != null ? processingBudget : Duration.ofSeconds(90);
        final Duration limit = PausedTenantsSync.LOCK_AT_MOST_FOR.minus(PausedTenantsSync.LOCK_MARGIN);
        if (processingBudget.isNegative() || processingBudget.isZero() || processingBudget.compareTo(limit) > 0) {
            throw new IllegalArgumentException("tenant-pause.processing-budget must be positive and at most "
                    + limit + " (the sync's lock time less a margin), was " + processingBudget);
        }
    }
}
