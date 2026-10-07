package com.incidentplatform.notification.channel;

/**
 * Why a channel could not deliver a notification, in the platform's own words
 * (backlog #0-93).
 *
 * <p>What a failed send records ({@code notification_log.error_message} and
 * the {@code NOTIFICATION_FAILED} audit event, which the tenant reads through
 * incident-service's audit API) used to be the channel's exception message,
 * built by appending the mail library's or the Slack client's: an SMTP
 * server's reply, the platform's relay host and port, a Slack response body.
 * Now a channel maps its provider's failure to one of these, the way mail and
 * messaging providers report a delivery (SES bounce types, Twilio error codes,
 * Slack's own {@code error} codes): a fixed name and description the platform
 * wrote, plus at most a short provider code it has checked
 * ({@link NotificationException#detail()}). The provider's raw text goes to the
 * log line written where the failure is caught, never to a row.
 *
 * <p>{@link #permanent()} says whether sending again could help: a rejected
 * address or a Slack channel the bot is not in will fail the same way until
 * the tenant changes something, an unreachable server may not. It sets the log
 * level today (WARN for the tenant's problem, ERROR for the platform's) and is
 * what a per-channel retry (backlog #0-32) would read.
 */
public enum NotificationFailureReason {

    /** The mail server refused the recipient (SMTP 5xx on RCPT), or the address is malformed. */
    EMAIL_RECIPIENT_REJECTED(true, "The mail server rejected the recipient address"),
    /** The mail server could not be reached, timed out, or deferred the message (4xx). */
    EMAIL_TRANSPORT_UNAVAILABLE(false, "The mail server could not be reached"),
    /** The platform could not authenticate to its mail server: the operator's to fix. */
    EMAIL_AUTHENTICATION_FAILED(false, "The platform could not authenticate to its mail server"),
    /** Any other mail failure. */
    EMAIL_FAILED(false, "Email delivery failed"),

    /** Slack refused the message ({@code ok:false} or HTTP 4xx); its code is the detail. */
    SLACK_REJECTED(true, "Slack refused the message"),
    /**
     * Slack no longer accepts the tenant's bot token ({@code invalid_auth},
     * {@code token_revoked}, {@code account_inactive}..., or HTTP 401): every
     * Slack notification of the tenant fails until the app is installed again.
     * Kept apart from {@link #SLACK_REJECTED} (second review) so it is not lost
     * among channels the bot is not in, and not permanent, so it is logged at
     * ERROR: a reinstall fixes it without anything in the message changing.
     */
    SLACK_AUTH_FAILED(false, "Slack no longer accepts the tenant's Slack app credentials"),
    /** Slack answered 429, or {@code ok:false} with {@code ratelimited}. */
    SLACK_RATE_LIMITED(false, "Slack rate-limited the platform"),
    /** Slack could not be reached, answered 5xx or a transient error, or an unreadable response. */
    SLACK_UNAVAILABLE(false, "Slack could not be reached"),
    /** The tenant has no active Slack workspace. */
    SLACK_WORKSPACE_MISSING(true, "No active Slack workspace for this tenant"),
    /** auth-service could not say which Slack workspace the tenant has. */
    SLACK_WORKSPACE_UNAVAILABLE(false, "The tenant's Slack workspace could not be read"),
    /** Nothing to post to: not a Slack user id, and the shared-channel broadcast is off. */
    SLACK_NOTHING_POSTED(true, "Nothing posted: no Slack user id and no broadcast channel");

    private final boolean permanent;
    private final String description;

    NotificationFailureReason(boolean permanent, String description) {
        this.permanent = permanent;
        this.description = description;
    }

    /** Whether the same send would fail the same way until something is changed. */
    public boolean permanent() {
        return permanent;
    }

    /** A fixed, platform-written description, safe to store and show to the tenant. */
    public String description() {
        return description;
    }
}
