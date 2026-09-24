package com.incidentplatform.notification.domain;

/**
 * Why a notification could not be delivered to anyone in its tenant
 * (backlog #0-18). Stored on the queue entry and used as a low-cardinality
 * metric tag, so add values sparingly.
 */
public enum UndeliverableReason {

    /** No on-call contact could be found for the tenant (nobody on call). */
    NO_ONCALL,

    /**
     * A contact was found, but none of the enabled channels for this event
     * type has an address for them (for example no email, no Slack id and no
     * phone, or only channels that are disabled).
     */
    NO_REACHABLE_CHANNEL,

    /**
     * oncall-service could not be reached or rejected the call for longer
     * than the retry window (backlog #0-19), so it is unknown whether anyone
     * is on call.
     */
    ONCALL_UNAVAILABLE,

    /**
     * Slack was the on-call contact's only reachable channel and auth-service
     * could not answer the tenant's Slack-workspace lookup for longer than the
     * retry window (backlog #0-21). Only used when nothing else could be sent —
     * with another reachable channel, Slack is skipped and the rest goes out
     * (see {@code SlackWorkspaceLookupUnavailableException}).
     */
    SLACK_WORKSPACE_UNAVAILABLE
}
