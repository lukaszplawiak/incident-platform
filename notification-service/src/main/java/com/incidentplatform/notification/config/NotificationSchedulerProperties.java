package com.incidentplatform.notification.config;

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
        Duration pendingThreshold

) {}