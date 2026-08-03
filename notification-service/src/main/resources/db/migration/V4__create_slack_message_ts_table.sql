-- Replaces SlackMessageStore's previous in-memory ConcurrentHashMap.
--
-- Stores the Slack message timestamp ("ts") needed to update a previously
-- sent Slack message via chat.update (e.g. after the incident is
-- acknowledged, to change the message to show "Acknowledged by X").
--
-- Why this moved out of memory:
--   The in-memory map did not survive across notification-service replicas
--   (Kubernetes HPA can scale this service to multiple pods) or restarts.
--   If a Slack "Acknowledge" button click was routed to a different pod
--   than the one that originally sent the notification, the update-after-
--   ack step would silently fail to find the stored ts for that channel.
--   The incident acknowledgment itself was never affected (that call goes
--   directly to incident-service, independent of this lookup) — only the
--   Slack message's visual update could be missed.
--
-- Why Postgres, not Redis: notification-service already has Postgres
-- (notification_log, notification_queue) and no Redis today. This data
-- doesn't need Redis-specific semantics (atomic increment, distributed
-- rate limiting) — it's a plain keyed lookup, well served by a table this
-- service's database already provides. Cleanup is a scheduled DELETE
-- (see NotificationScheduler.cleanupOldSlackMessageTs), not Redis TTL.

CREATE TABLE slack_message_ts
(
    incident_id UUID         NOT NULL,
    channel     VARCHAR(255) NOT NULL,
    tenant_id   VARCHAR(255) NOT NULL,
    ts          VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_slack_message_ts PRIMARY KEY (incident_id, channel)
);

-- Supports "find all channels for this incident" (used when updating every
-- Slack message for an incident after acknowledgment, not just the one the
-- button was clicked in).
CREATE INDEX idx_slack_message_ts_incident
    ON slack_message_ts (incident_id);

-- Supports the cleanup job's "delete entries older than retention period" query.
CREATE INDEX idx_slack_message_ts_created_at
    ON slack_message_ts (created_at);

COMMENT ON TABLE slack_message_ts
    IS 'Stores Slack message timestamps for chat.update after incident '
       'acknowledgment. Rows are deleted either explicitly after an ACK '
       '(all channels for that incident) or by a scheduled cleanup job for '
       'incidents that were never acknowledged via Slack.';