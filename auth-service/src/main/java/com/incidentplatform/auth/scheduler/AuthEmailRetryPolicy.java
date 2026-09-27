package com.incidentplatform.auth.scheduler;

import com.incidentplatform.auth.config.InviteEmailProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Decides when a failed auth email is tried again, or that it is not
 * (backlog #0-52).
 *
 * <h2>Why a deadline, not a count</h2>
 * The old policy gave up after 3 attempts 5 minutes apart, so any SMTP outage
 * longer than about 15 minutes lost every invite and password reset sent in
 * it. What decides whether another attempt is worth making is not how many
 * came before but whether the request is still worth sending: an invite for 7
 * days, a password reset for 15 minutes (the entry's {@code deadline}, the
 * lifetime of the token the email carries — created when it is sent). Mail
 * servers (RFC 5321 §4.5.4.1, Postfix) and webhook senders (Stripe) likewise
 * retry with a growing, capped delay for a fixed time, not a fixed count;
 * notification-service bounds its oncall lookup retry the same way
 * ({@code NOTIFICATION_LOOKUP_RETRY_WINDOW}).
 *
 * <h2>Schedule</h2>
 * After the n-th failure the next attempt is {@code backoff[n-1]} later; the
 * last delay repeats. An attempt that would fall after the deadline is moved
 * to the deadline itself (one last try); once the deadline has passed, the
 * email is given up. With the defaults (1m, 5m, 30m, 2h, 6h) a reset gets
 * about 4 attempts in 15 minutes and an invite about 33 in 7 days.
 *
 * <p>No jitter: one ShedLock'd scheduler sends, in capped batches, so retries
 * cannot stampede the SMTP server, and a deterministic schedule is testable.
 */
public class AuthEmailRetryPolicy {

    private final List<Duration> backoff;

    public AuthEmailRetryPolicy(InviteEmailProperties properties) {
        this(properties.retryBackoff());
    }

    AuthEmailRetryPolicy(List<Duration> backoff) {
        if (backoff == null || backoff.isEmpty()) {
            throw new IllegalArgumentException("retry backoff must not be empty");
        }
        this.backoff = List.copyOf(backoff);
    }

    /**
     * @param failures failed sends so far, including the one just made (≥ 1)
     * @param now      when that send failed
     * @param deadline until when the request is worth sending
     * @return when to try again, or empty to give up
     */
    public Optional<Instant> nextAttempt(int failures, Instant now, Instant deadline) {
        if (failures < 1) {
            throw new IllegalArgumentException("failures must be at least 1, got " + failures);
        }
        final Duration delay = backoff.get(Math.min(failures, backoff.size()) - 1);
        final Instant regular = now.plus(delay);
        if (!regular.isAfter(deadline)) {
            return Optional.of(regular);
        }
        if (now.isBefore(deadline)) {
            return Optional.of(deadline);
        }
        return Optional.empty();
    }
}
