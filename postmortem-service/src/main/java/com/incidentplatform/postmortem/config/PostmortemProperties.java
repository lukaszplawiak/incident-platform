package com.incidentplatform.postmortem.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Strongly-typed, validated configuration for postmortem generation scheduling.
 *
 * <p>Replaces four {@code @Value} injections in
 * {@link com.incidentplatform.postmortem.scheduler.PostmortemRetryScheduler}:
 * <ul>
 *   <li>{@code postmortem.max-retry-attempts}</li>
 *   <li>{@code postmortem.stuck-threshold-minutes}</li>
 *   <li>{@code postmortem.generating-batch-size}</li>
 *   <li>{@code postmortem.retry-batch-size}</li>
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
 *   generating-batch-size: ${POSTMORTEM_GENERATING_BATCH_SIZE:10}
 *   retry-batch-size: ${POSTMORTEM_RETRY_BATCH_SIZE:20}
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
        Duration stuckThreshold,

        /**
         * Fixed (backlog #48): caps how many GENERATING records
         * {@code PostmortemRetryScheduler.processGenerating()} processes
         * per scheduled run — previously unbounded (loaded and processed
         * every matching row every run). Each item costs a real Gemini API
         * call (3–15s per {@code GeminiClient}'s own documentation) plus a
         * DB write, so a large backlog (e.g. after an outage) could
         * genuinely exceed {@code lockAtMostFor = "4m"} on that method —
         * ShedLock would then release the lock mid-processing, risking a
         * second instance picking up the same batch concurrently. Default
         * 10 leaves roughly 29% margin against the 240s budget at a 17s/
         * item worst case (15s Gemini + DB write overhead). Matches the
         * same {@code Pageable}-batching pattern already established for
         * {@code EscalationScheduler} (backlog #39) and
         * {@code IncidentEventOutboxScheduler} (backlog #36) — deliberately
         * smaller here than {@code escalation.scheduler-batch-size}'s
         * default of 100, since each item here costs orders of magnitude
         * more time than an HTTP call to oncall-service.
         */
        @Positive(message = "postmortem.generating-batch-size must be positive")
        int generatingBatchSize,

        /**
         * Same reasoning as {@link #generatingBatchSize}, for
         * {@code retryFailedPostmortems()} (backlog #48). Default 20
         * leaves roughly 37% margin against that method's larger
         * {@code lockAtMostFor = "9m"} (540s) budget at the same 17s/item
         * worst case.
         */
        @Positive(message = "postmortem.retry-batch-size must be positive")
        int retryBatchSize

) {}