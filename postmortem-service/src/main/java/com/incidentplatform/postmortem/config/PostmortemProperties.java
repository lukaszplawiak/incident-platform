package com.incidentplatform.postmortem.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Strongly-typed, validated configuration for postmortem generation scheduling.
 *
 * <p>Replaces two {@code @Value} injections in
 * {@link com.incidentplatform.postmortem.scheduler.PostmortemRetryScheduler}:
 * <ul>
 *   <li>{@code postmortem.max-retry-attempts}</li>
 *   <li>{@code postmortem.stuck-threshold-minutes}</li>
 * </ul>
 *
 * <p>Note: {@code postmortem.retry-scheduler-interval-ms} and
 * {@code postmortem.generating-scheduler-interval-ms} are referenced via
 * {@code @Scheduled(fixedDelayString = "${...}")} which only supports property
 * placeholders — not bean SpEL. These are therefore kept as plain properties
 * in {@code application.yml} and not included in this record.
 *
 * <h2>YAML configuration</h2>
 * <pre>{@code
 * postmortem:
 *   max-retry-attempts: ${POSTMORTEM_MAX_RETRY_ATTEMPTS:3}
 *   stuck-threshold-minutes: ${POSTMORTEM_STUCK_THRESHOLD_MINUTES:2}
 *   # Kept as plain properties for @Scheduled(fixedDelayString):
 *   generating-scheduler-interval-ms: ${POSTMORTEM_GENERATING_INTERVAL_MS:30000}
 *   retry-scheduler-interval-ms: ${POSTMORTEM_RETRY_INTERVAL_MS:300000}
 * }</pre>
 */
@ConfigurationProperties(prefix = "postmortem")
@Validated
public record PostmortemProperties(

        /**
         * Maximum number of Gemini generation attempts before a postmortem
         * is marked PERMANENTLY_FAILED. Default: 3.
         */
        @Positive(message = "postmortem.max-retry-attempts must be positive")
        int maxRetryAttempts,

        /**
         * How long a GENERATING postmortem must have been stuck before the
         * scheduler considers it stale and reprocesses it. Prevents the
         * scheduler from racing against a Kafka consumer that just wrote the
         * outbox entry. Default: PT2M (2 minutes).
         */
        @NotNull(message = "postmortem.stuck-threshold must not be null")
        Duration stuckThreshold

) {}