package com.incidentplatform.notification.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration for the notification outbox scheduler.
 *
 * <p>Replaces one {@code @Value} injection in
 * {@link com.incidentplatform.notification.scheduler.NotificationScheduler}:
 * {@code notification.scheduler.pending-threshold-seconds}.
 *
 * <p>Note: {@code notification.scheduler.interval-ms} is referenced via
 * {@code @Scheduled(fixedDelayString = "${...}")} which only supports property
 * placeholders — not bean SpEL. It is therefore kept as a plain property in
 * {@code application.yml} and not included in this record.
 *
 * <h2>YAML configuration</h2>
 * <pre>{@code
 * notification:
 *   scheduler:
 *     pending-threshold: ${NOTIFICATION_SCHEDULER_PENDING_THRESHOLD:PT30S}
 *     interval-ms: ${NOTIFICATION_SCHEDULER_INTERVAL_MS:30000}
 *     slack-message-ts-retention: ${NOTIFICATION_SLACK_TS_RETENTION:P7D}
 *     lookup-retry-window: ${NOTIFICATION_LOOKUP_RETRY_WINDOW:PT10M}
 *     processing-budget: ${NOTIFICATION_SCHEDULER_PROCESSING_BUDGET:PT3M}
 *     batch-size: ${NOTIFICATION_SCHEDULER_BATCH_SIZE:200}
 * }</pre>
 */
@ConfigurationProperties(prefix = "notification.scheduler")
@Validated
public record NotificationSchedulerProperties(

        /**
         * How long a PENDING outbox entry must exist before the scheduler
         * picks it up. Prevents racing against a Kafka consumer that just
         * committed within the same scheduler cycle.
         * Default: PT30S (30 seconds).
         */
        @NotNull(message = "notification.scheduler.pending-threshold must not be null")
        Duration pendingThreshold,

        /**
         * How long a {@code slack_message_ts} row is kept before the
         * cleanup job deletes it. Rows for incidents acknowledged via
         * Slack are deleted immediately after the ACK update; this
         * threshold only matters for incidents acknowledged some other
         * way (e.g. the web UI), whose rows would otherwise never be
         * cleaned up. Default: P7D (7 days) — matches the original design
         * intent noted in this table's Flyway migration comment.
         */
        @NotNull(message = "notification.scheduler.slack-message-ts-retention must not be null")
        Duration slackMessageTsRetention,

        /**
         * How long an entry is retried while oncall-service cannot answer the
         * lookup that decides who is notified (backlog #0-19). During the
         * window the entry stays PENDING and is picked up again on the next
         * cycle; after it the entry becomes UNDELIVERABLE and the operator is
         * told. Default: PT10M.
         */
        @NotNull(message = "notification.scheduler.lookup-retry-window must not be null")
        Duration lookupRetryWindow,

        /**
         * How long one scheduler run may keep processing entries before it stops and
         * leaves the rest for the next cycle. Must stay below the ShedLock
         * {@code lockAtMostFor} (4 minutes): a run that outlives the lock lets a second
         * replica start on the same PENDING entries. At least one entry is always
         * processed per run. Default: PT3M.
         */
        @NotNull(message = "notification.scheduler.processing-budget must not be null")
        Duration processingBudget,

        /**
         * At most this many PENDING entries are loaded per run, oldest first
         * (backlog #0-10). Default: 200.
         */
        @Min(value = 1, message = "notification.scheduler.batch-size must be at least 1")
        int batchSize

) {}