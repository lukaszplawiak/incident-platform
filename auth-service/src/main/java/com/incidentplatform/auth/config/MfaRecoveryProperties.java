package com.incidentplatform.auth.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Operator-assisted MFA recovery of a customer tenant's only admin (backlog
 * #0-90), {@code platform.mfa-recovery.*}.
 *
 * @param waitingPeriod      how long the account has been told before the
 *                           reset runs, counted from the send of its notice.
 *                           Default 72 h (a weekend plus a working day, the
 *                           span account-recovery waiting periods commonly
 *                           use); at least {@link #MIN_WAITING_PERIOD}, as the
 *                           delay is the defence against a deceived operator
 *                           (security review: a variable must not shrink it to
 *                           seconds), and at most {@link #MAX_WAITING_PERIOD},
 *                           so the notice's cancel link (14 days) outlives it.
 * @param schedulerIntervalMs delay between runs of the job that executes due
 *                           requests and expires undelivered ones
 * @param batchSize          requests of each kind one run takes
 */
@ConfigurationProperties(prefix = "platform.mfa-recovery")
@Validated
public record MfaRecoveryProperties(
        @NotNull Duration waitingPeriod,
        @Positive long schedulerIntervalMs,
        @Positive int batchSize) {

    /** The shortest waiting period, the platform API's 24 h for a new factor (#0-83). */
    public static final Duration MIN_WAITING_PERIOD = Duration.ofHours(24);

    /** The longest waiting period: shorter than the cancel link's 14 days. */
    public static final Duration MAX_WAITING_PERIOD = Duration.ofDays(7);

    public MfaRecoveryProperties {
        if (waitingPeriod != null && (waitingPeriod.compareTo(MIN_WAITING_PERIOD) < 0
                || waitingPeriod.compareTo(MAX_WAITING_PERIOD) > 0)) {
            throw new IllegalArgumentException("platform.mfa-recovery.waiting-period must be between "
                    + MIN_WAITING_PERIOD + " and " + MAX_WAITING_PERIOD + ", was " + waitingPeriod);
        }
    }
}
