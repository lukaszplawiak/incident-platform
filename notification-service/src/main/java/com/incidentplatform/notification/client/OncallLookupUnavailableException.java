package com.incidentplatform.notification.client;

/**
 * oncall-service could not answer a lookup that decides who is notified:
 * unreachable, timed out, circuit open, or it rejected the call (401/403).
 *
 * <h2>Added (backlog #0-19): a failed lookup is not "nobody on call"</h2>
 * {@code getCurrentOncall} and {@code findCurrentByUserId} used to fail open,
 * returning {@code Optional.empty()} for both a 204 (nobody on call) and an
 * outage. The router then treated the outage as "no on-call", the queue entry
 * was marked SENT and never retried, so every escalation in the window went to
 * the wrong destination and was never re-sent once oncall-service recovered.
 * These two lookups now throw this exception on failure; the scheduler leaves
 * the entry PENDING for a bounded retry window and only then gives up.
 *
 * <p>Every failure that is not a normal answer counts as "unavailable": a 5xx or an
 * I/O error, an open circuit, a 401/403 (a service-token problem), but also a 400/404
 * or an unparseable body. A permanent failure therefore waits out the retry window
 * (backlog #0-19) before the entry is parked as UNDELIVERABLE, instead of being read as
 * "nobody on call". That is deliberate: guessing that a non-auth 4xx means "nobody
 * there" is exactly the mistake this exception exists to prevent.
 *
 * <p>{@code findBySlackUserId} still fails open: it answers a user's own Slack
 * click, where an immediate empty answer is better than a retry.
 */
public class OncallLookupUnavailableException extends RuntimeException {

    public OncallLookupUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
