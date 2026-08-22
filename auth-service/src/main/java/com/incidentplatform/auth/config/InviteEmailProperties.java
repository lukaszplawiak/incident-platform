package com.incidentplatform.auth.config;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Strongly-typed, validated configuration for the invite email Outbox Pattern.
 *
 * <p>Replaces six scattered {@code @Value} injections across two classes
 * (referenced here by their current names — both were renamed from
 * {@code InviteEmailService}/{@code InviteEmailScheduler} to
 * {@code AuthEmail*} when this outbox was extended to also cover
 * password-reset emails, not just invites):
 * <ul>
 *   <li>{@code AuthEmailService}: {@code invite.email.from},
 *       {@code invite.email.app-base-url}</li>
 *   <li>{@code AuthEmailScheduler}: {@code invite.email.max-retry-attempts},
 *       {@code invite.email.pending-threshold-seconds},
 *       {@code invite.email.scheduler-interval-ms} (via {@code @Scheduled}),
 *       {@code invite.email.retry-interval-ms} (via {@code @Scheduled})</li>
 * </ul>
 *
 * <h2>Why a single record for both classes</h2>
 * All six properties share the {@code invite.email} prefix and describe a
 * single concern — the invite email flow. Splitting them across two records
 * would create unnecessary indirection. One record, one place to look.
 *
 * <h2>@Scheduled and @ConfigurationProperties</h2>
 * {@code @Scheduled(fixedDelayString = "${...}")} does not support SpEL
 * expressions referencing beans — only property placeholders. The scheduler
 * interval and retry interval are therefore kept as millisecond {@code long}
 * values (not {@link Duration}) so they can still be referenced from
 * {@code @Scheduled(fixedDelayString = "...")} via property placeholders.
 * All other properties are strongly typed.
 *
 * <h2>YAML configuration</h2>
 * <pre>{@code
 * invite:
 *   email:
 *     from: ${INVITE_EMAIL_FROM:noreply@incidentplatform.com}
 *     app-base-url: ${APP_BASE_URL:http://localhost:3000}
 *     max-retry-attempts: ${INVITE_EMAIL_MAX_RETRIES:3}
 *     pending-threshold-seconds: ${INVITE_EMAIL_PENDING_THRESHOLD_SECONDS:30}
 *     batch-size: ${INVITE_EMAIL_BATCH_SIZE:15}
 *     scheduler-interval-ms: ${INVITE_EMAIL_SCHEDULER_INTERVAL_MS:30000}
 *     retry-interval-ms: ${INVITE_EMAIL_RETRY_INTERVAL_MS:300000}
 * }</pre>
 */
@ConfigurationProperties(prefix = "invite.email")
@Validated
public record InviteEmailProperties(

        /**
         * Sender address for invite emails.
         * Must be a valid email address accepted by the configured SMTP server.
         */
        @NotBlank(message = "invite.email.from must not be blank")
        String from,

        /**
         * Base URL of the frontend application. Used to build the invite link:
         * {@code {appBaseUrl}/accept-invite?token={rawToken}}
         */
        @NotBlank(message = "invite.email.app-base-url must not be blank")
        String appBaseUrl,

        /**
         * Maximum number of send attempts before an outbox entry is marked
         * PERMANENTLY_FAILED. Default: 3.
         */
        @Positive(message = "invite.email.max-retry-attempts must be positive")
        int maxRetryAttempts,

        /**
         * How long after creation a PENDING outbox entry must be before the
         * scheduler picks it up. Prevents racing against a UserService that
         * just committed within the same scheduler cycle. Spring Boot maps
         * {@code pending-threshold} → {@code pendingThreshold} (Duration).
         * ISO-8601 format: PT30S. Default: PT30S (30 seconds).
         */
        @NotNull(message = "invite.email.pending-threshold must not be null")
        Duration pendingThreshold,

        /**
         * Fixed (backlog #54): caps how many outbox entries
         * {@code AuthEmailScheduler.processPending()}/{@code retryFailed()}
         * process per scheduled run — previously unbounded. Each item
         * costs a real, blocking SMTP send, so a large backlog (e.g. a
         * bulk-invite of a new team, or a period of SMTP degradation)
         * could genuinely approach or exceed {@code lockAtMostFor = "4m"}
         * on either method — ShedLock would then release the lock
         * mid-batch, risking a second instance picking up the same batch
         * concurrently and sending duplicate emails. Default 15 leaves
         * roughly 37% margin against the 240s budget at a 10s/item worst
         * case (SMTP relays are prone to throttling/greylisting delays).
         *
         * <p>A single shared property, not split into separate
         * generating/retry batch sizes the way
         * {@code PostmortemProperties} does (backlog #48) — that split
         * exists there specifically because {@code processGenerating}
         * and {@code retryFailedPostmortems} have genuinely different
         * {@code lockAtMostFor} budgets (4m vs 9m). Both scheduled
         * methods here share the identical 4m budget, so one property
         * correctly reflects that there's only one constraint being
         * protected, not two.
         */
        @Positive(message = "invite.email.batch-size must be positive")
        int batchSize,

        /**
         * Fixed delay between scheduler runs for PENDING entries (milliseconds).
         * Kept as {@code long} for use in {@code @Scheduled(fixedDelayString)}.
         * Default: 30 000 ms (30 seconds).
         */
        @Positive(message = "invite.email.scheduler-interval-ms must be positive")
        long schedulerIntervalMs,

        /**
         * Fixed delay between retry scheduler runs for FAILED entries (milliseconds).
         * Kept as {@code long} for use in {@code @Scheduled(fixedDelayString)}.
         * Default: 300 000 ms (5 minutes).
         */
        @Positive(message = "invite.email.retry-interval-ms must be positive")
        long retryIntervalMs

) {}