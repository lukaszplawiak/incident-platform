package com.incidentplatform.shared.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings of a service's audit outbox (backlog #0-84). The relay's schedule
 * is set by {@code audit.outbox.poll-interval-ms} (default 2,000) and
 * {@code audit.outbox.purge-interval-ms} (default one hour), read by its
 * {@code @Scheduled} annotations. Setting {@code table}
 * turns the outbox on for the service: {@link AuditEventPublisher} then writes
 * every audit event to that table, in the caller's transaction, and
 * {@link AuditOutboxRelay} sends it to Kafka. Without it the service has no
 * publisher at all.
 *
 * @param table        the service's own outbox table, created by its Flyway
 *                     migrations (one database, one table per service:
 *                     {@code <service>_audit_outbox})
 * @param batchSize        events handed to Kafka at once (default 100)
 * @param maxBatchesPerRun full batches one relay run takes at most (default 10)
 * @param sendTimeout      how long the relay waits for a batch's
 *                         acknowledgements (default 5 s)
 * @param retention        how long sent events stay in the table, for
 *                         diagnosis (default 7 days)
 */
@ConfigurationProperties(prefix = "audit.outbox")
public record AuditOutboxProperties(
        String table,
        Integer batchSize,
        Integer maxBatchesPerRun,
        Duration sendTimeout,
        Duration retention
) {

    public AuditOutboxProperties {
        if (table != null) {
            // Interpolated into SQL and into the relay's lock names: checked at startup.
            AuditOutbox.checkedTableName(table);
        }
        batchSize = batchSize != null ? batchSize : 100;
        maxBatchesPerRun = maxBatchesPerRun != null ? maxBatchesPerRun : 10;
        sendTimeout = sendTimeout != null ? sendTimeout : Duration.ofSeconds(5);
        retention = retention != null ? retention : Duration.ofDays(7);
        if (batchSize < 1) {
            throw new IllegalArgumentException("audit.outbox.batch-size must be at least 1, was " + batchSize);
        }
        if (maxBatchesPerRun < 1) {
            throw new IllegalArgumentException("audit.outbox.max-batches-per-run must be at least 1, was "
                    + maxBatchesPerRun);
        }
        if (sendTimeout.isNegative() || sendTimeout.isZero()) {
            throw new IllegalArgumentException("audit.outbox.send-timeout must be positive, was " + sendTimeout);
        }
        if (retention.isNegative() || retention.isZero()) {
            // Zero would purge a row the moment it is sent, a negative value
            // rows not yet old enough to purge.
            throw new IllegalArgumentException("audit.outbox.retention must be positive, was " + retention);
        }
    }
}
