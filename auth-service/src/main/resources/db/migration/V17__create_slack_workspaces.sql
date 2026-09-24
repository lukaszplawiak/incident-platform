-- Per-tenant Slack workspace connections (backlog #0-21).
--
-- Problem this replaces: notification.channels.slack used one global bot
-- token/channel/broadcast-enabled for the whole platform (see
-- NotificationChannelProperties in notification-service before this
-- migration). In a multi-tenant deployment each customer has its own Slack
-- workspace — one bot token cannot DM their users — so Slack only ever
-- worked for the one tenant whose engineers happened to sit in the
-- platform's own workspace.
--
-- Model: one active Slack workspace connection per tenant, installed by a
-- tenant admin pasting a bot token they created in their own Slack App
-- (manual entry — no OAuth install flow; see backlog #0-21's recorded
-- decision). Read cross-service by notification-service via a narrow,
-- ROLE_SERVICE-only internal endpoint (backlog #0-30).
--
-- Deliberately NOT modeled like `integrations`/`api_keys`:
--   - team_id is a plain nullable UUID column with a real FK, but the JPA
--     entity does NOT model it as a @ManyToOne relation (unlike
--     Integration.team) — see backlog #0-31: Integration/ApiKey's eager
--     User/Team object-graph traversal on the API-key auth hot path is
--     exactly the coupling that made a future auth-service split hard, and
--     this table is built from day one to not repeat it.
--   - no relation to users at all (no "owner"), unlike api_keys.owner_user_id.
--   - has @Version from day one (backlog #0-25 exists only because
--     NotificationQueueEntry skipped this on a mutable entity).
--
-- bot_token_encrypted: AES-256-GCM via a *separate* encryption key
-- (slack.encryption-key, not mfa.encryption-key) — see AesEncryptionConfig.
-- Same output format as the existing MFA secret encryption:
-- base64(iv):base64(ciphertext+tag).

CREATE TABLE slack_workspaces
(
    id                   UUID         NOT NULL DEFAULT gen_random_uuid(),
    tenant_id            VARCHAR(255) NOT NULL,
    team_id              UUID         REFERENCES teams (id) ON DELETE SET NULL,
    slack_team_id        VARCHAR(255) NOT NULL,      -- Slack's own workspace id, e.g. "T0123456"
    bot_token_encrypted  TEXT         NOT NULL,
    default_channel      VARCHAR(255),
    broadcast_enabled    BOOLEAN      NOT NULL DEFAULT FALSE,
    installed_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    revoked_at           TIMESTAMPTZ,                -- soft delete, mirrors integrations.revoked_at
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version              BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT pk_slack_workspaces
        PRIMARY KEY (id),

    CONSTRAINT chk_slack_workspaces_slack_team_id_not_blank
        CHECK (btrim(slack_team_id) <> '')
);

-- One active Slack workspace per tenant.
CREATE UNIQUE INDEX uq_slack_workspaces_active_tenant
    ON slack_workspaces (tenant_id)
    WHERE revoked_at IS NULL;

CREATE INDEX idx_slack_workspaces_team
    ON slack_workspaces (team_id)
    WHERE team_id IS NOT NULL AND revoked_at IS NULL;

COMMENT ON TABLE slack_workspaces
    IS 'One tenant''s Slack workspace connection (backlog #0-21). Bot token is '
       'admin-pasted and AES-256-GCM encrypted with a dedicated key '
       '(slack.encryption-key). Read cross-service by notification-service '
       'via a ROLE_SERVICE-only internal endpoint (backlog #0-30).';

COMMENT ON COLUMN slack_workspaces.team_id
    IS 'Optional team scoping for a future team-scoped channel (not enforced '
       'in routing yet). Plain FK column, not a JPA relation — see backlog '
       '#0-31 on why Integration.team''s eager-fetch pattern is avoided here.';

COMMENT ON COLUMN slack_workspaces.bot_token_encrypted
    IS 'AES-256-GCM: base64(iv):base64(ciphertext+tag). Never returned to the '
       'admin UI after install — only the internal service-read endpoint '
       'decrypts it.';
