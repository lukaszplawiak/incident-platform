package com.incidentplatform.notification.client;

/**
 * auth-service could not answer the Slack-workspace lookup (backlog #0-21/#0-30):
 * unreachable, timed out, circuit open, it rejected the service token (401/403),
 * or it returned something other than a normal answer (5xx, unparseable body).
 *
 * <h2>Why a separate exception, not {@code Optional.empty()}</h2>
 * An empty result means "this tenant has no active Slack workspace" — a normal,
 * permanent configuration fact. An outage means "we don't know". Collapsing the
 * two (as the first implementation of {@link SlackWorkspaceClientImpl} did) makes
 * an auth-service outage look to every caller like a tenant that chose not to use
 * Slack, and makes the difference impossible to log or test at the call site.
 *
 * <h2>Why callers catch it instead of letting it reach the scheduler (unlike #0-19)</h2>
 * {@link OncallLookupUnavailableException} propagates to {@code NotificationScheduler},
 * which leaves the whole queue entry PENDING — correct there, because the on-call
 * lookup decides <em>who</em> is notified, so sending anything without it would go
 * to the wrong person. This lookup decides only whether <em>one channel</em> (Slack)
 * can be used. Propagating it would make auth-service a hard dependency of email and
 * SMS delivery: an auth-service outage would delay every channel and, after the retry
 * window, park the entry as UNDELIVERABLE although email could have gone out
 * immediately. So {@code NotificationRouter} catches it and skips only SLACK (the
 * fallback has already recorded {@code ClientFallbackMetrics}); the Slack message for
 * that notification is not retried — the same "at most once per channel" semantics a
 * live Slack API outage already has today. Per-channel retry for every channel and
 * cause is tracked as backlog #0-32.
 *
 * <p>One exception to "caught in the router": when Slack was the contact's
 * <em>only</em> reachable channel, the router rethrows it. Nothing else would be
 * sent, so holding the entry delays nothing, and parking it as UNDELIVERABLE on a
 * transient outage would repeat the #0-19 mistake. {@code NotificationScheduler}
 * then treats it like the on-call lookup: PENDING within the retry window,
 * {@code SLACK_WORKSPACE_UNAVAILABLE} after it.
 */
public class SlackWorkspaceLookupUnavailableException extends RuntimeException {

    public SlackWorkspaceLookupUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
