package com.incidentplatform.auth.config;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

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
 *   <li>{@code AuthEmailScheduler}: {@code invite.email.batch-size},
 *       {@code invite.email.retry-backoff}, {@code invite.email.processing-budget},
 *       {@code invite.email.retention},
 *       {@code invite.email.scheduler-interval-ms} (via {@code @Scheduled})</li>
 * </ul>
 *
 * <h2>Changed (backlog #0-52): retries are bounded by time, not a count</h2>
 * {@code max-retry-attempts} (3) and {@code retry-interval-ms} (5 min) are
 * gone: together they gave up after about 15 minutes of SMTP trouble. A failed
 * email is now retried on the {@code retry-backoff} schedule until the
 * request's deadline (its token lifetime) — see {@code AuthEmailRetryPolicy}.
 * {@code pending-threshold} is gone too: it delayed new entries by 30 s to
 * avoid "racing" the transaction that wrote them, but an uncommitted row is not
 * visible to the scheduler in the first place.
 *
 * <h2>Why a single record for both classes</h2>
 * All six properties share the {@code invite.email} prefix and describe a
 * single concern — the invite email flow. Splitting them across two records
 * would create unnecessary indirection. One record, one place to look.
 *
 * <h2>@Scheduled and @ConfigurationProperties</h2>
 * {@code @Scheduled(fixedDelayString = "${...}")} does not support SpEL
 * expressions referencing beans — only property placeholders. The scheduler
 * interval is therefore kept as a millisecond {@code long} value (not
 * {@link Duration}) so it can still be referenced from
 * {@code @Scheduled(fixedDelayString = "...")} via a property placeholder.
 * All other properties are strongly typed.
 *
 * <h2>YAML configuration</h2>
 * <pre>{@code
 * invite:
 *   email:
 *     from: ${INVITE_EMAIL_FROM:noreply@incidentplatform.com}
 *     app-base-url: ${APP_BASE_URL:http://localhost:3000}
 *     batch-size: ${INVITE_EMAIL_BATCH_SIZE:15}
 *     scheduler-interval-ms: ${INVITE_EMAIL_SCHEDULER_INTERVAL_MS:30000}
 *     retry-backoff: ${INVITE_EMAIL_RETRY_BACKOFF:PT1M,PT5M,PT30M,PT2H,PT6H}
 *     processing-budget: ${INVITE_EMAIL_PROCESSING_BUDGET:PT2M}
 *     retention: ${INVITE_EMAIL_RETENTION:P30D}
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
         * Fixed (backlog #54): caps how many outbox entries
         * {@code AuthEmailScheduler.processPending()}/{@code retryFailed()}
         * each process per scheduled run — previously unbounded. Each item
         * costs a real, blocking SMTP send, so a large backlog (e.g. a
         * bulk-invite of a new team, or a period of SMTP degradation)
         * could genuinely approach or exceed {@code lockAtMostFor = "4m"}
         * on either method — ShedLock would then release the lock
         * mid-batch, risking a second instance picking up the same batch
         * concurrently and sending duplicate emails. Default 15. Corrected
         * (backlog #0-52): the "10 s per item" worst case this relied on was
         * too low (see {@link #processingBudget}), so since #0-52 the
         * processing budget, not this cap, keeps a run inside the lock.
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
         * Fixed delay between scheduler runs, which pick up every PENDING and
         * FAILED entry that is due (milliseconds). Kept as {@code long} for use
         * in {@code @Scheduled(fixedDelayString)}. Default: 30 000 ms (30 s).
         */
        @Positive(message = "invite.email.scheduler-interval-ms must be positive")
        long schedulerIntervalMs,

        /**
         * Backlog #0-52: delay before the n-th retry after the n-th failed
         * send; the last value repeats. Default: PT1M, PT5M, PT30M, PT2H, PT6H
         * (the shape of MTA and webhook retry schedules: short first, then
         * capped). Every value must be positive.
         */
        @NotEmpty(message = "invite.email.retry-backoff must not be empty")
        List<@NotNull Duration> retryBackoff,

        /**
         * Backlog #0-52 (performance review): how long one scheduler run may
         * keep taking entries. SMTP timeouts apply per socket operation, and
         * one email is about ten of them, so a slow server can hold a single
         * send for close to a minute: {@code batch-size} alone cannot keep a
         * run inside its ShedLock. Must leave the lock time for one more send;
         * {@code AuthEmailScheduler} checks that at startup. Default: PT2M.
         */
        @NotNull(message = "invite.email.processing-budget must not be null")
        Duration processingBudget,

        /**
         * Backlog #0-52: how long SENT, PERMANENTLY_FAILED and SUPERSEDED
         * entries are kept before the daily purge deletes them. Default: P30D.
         */
        @NotNull(message = "invite.email.retention must not be null")
        Duration retention

) {

    public InviteEmailProperties {
        if (retryBackoff != null) {
            for (final Duration delay : retryBackoff) {
                if (delay == null || delay.isZero() || delay.isNegative()) {
                    throw new IllegalArgumentException(
                            "invite.email.retry-backoff values must be positive, got " + retryBackoff);
                }
            }
            retryBackoff = List.copyOf(retryBackoff);
        }
        if (retention != null && (retention.isZero() || retention.isNegative())) {
            throw new IllegalArgumentException(
                    "invite.email.retention must be positive, got " + retention);
        }
    }
}