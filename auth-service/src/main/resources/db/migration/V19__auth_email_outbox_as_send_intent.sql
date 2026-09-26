-- Backlog #0-52: the auth email outbox becomes a record of the INTENT to send
-- an invite or password-reset email. The token is created when the email is
-- sent, not when it is requested, and the raw token is never stored.
--
-- Before (V5, V7): the request path created the token and wrote its raw value
-- into raw_token, the row carried an FK to that token, and at most one
-- PENDING/FAILED row per user and type was allowed. That gave the row two
-- writers (the scheduler, and resend-invite / forgot-password closing a FAILED
-- row before inserting a new one), kept a usable credential in plaintext for as
-- long as the email was being retried (backlog #0-54), and let the token's
-- lifetime run while the email waited on a broken SMTP server.
--
-- Now:
--   * The request path only INSERTs a row. AuthEmailScheduler is the only
--     writer after that: it closes an older row itself when a newer request for
--     the same user and type exists (SUPERSEDED), so no uniqueness rule makes the
--     request path touch existing rows.
--   * Per attempt the scheduler invalidates the user's earlier tokens of that
--     type, creates a new one (only its SHA-256 is stored, in auth_tokens) and
--     sends it. The link is valid for the full lifetime from the moment it is
--     sent.
--   * deadline: until when the request is worth sending (created_at + the
--     token lifetime: 7 days for an invite, 15 minutes for a reset). Retries
--     stop there (AuthEmailRetryPolicy).
--   * tenant_id is copied onto the row (as email already was), so the
--     scheduler reads flat rows without joining users.
--   * No FK to auth_tokens: token cleanup no longer deletes outbox rows by
--     cascade. AuthEmailScheduler purges terminal rows after a retention period.
--
-- The table is dropped, not migrated: the platform is not in production and the
-- rows hold nothing worth keeping (raw tokens of short-lived links). A pending
-- invite at the time of the upgrade needs a resend-invite. Once the platform
-- runs in production, schema changes must instead stay compatible with the
-- previous release (expand/contract), since pods of both run during a rollout.

DROP TABLE auth_email_outbox;

CREATE TABLE auth_email_outbox
(
    id              UUID         NOT NULL,
    user_id         UUID         NOT NULL
        REFERENCES users (id) ON DELETE CASCADE,
    tenant_id       VARCHAR(255) NOT NULL,
    email           VARCHAR(255) NOT NULL,
    email_type      VARCHAR(30)  NOT NULL,
    status          VARCHAR(30)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    error_message   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL,
    deadline        TIMESTAMPTZ  NOT NULL,
    next_attempt_at TIMESTAMPTZ,
    sent_at         TIMESTAMPTZ,

    CONSTRAINT pk_auth_email_outbox PRIMARY KEY (id),
    CONSTRAINT chk_auth_email_outbox_type
        CHECK (email_type IN ('INVITE', 'PASSWORD_RESET')),
    CONSTRAINT chk_auth_email_outbox_status
        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'PERMANENTLY_FAILED', 'SUPERSEDED')),
    -- An entry the scheduler may still send always has a time to do so, so a
    -- FAILED row can never silently drop out of its query (the backlog #81 class
    -- of bug); a terminal row has none.
    CONSTRAINT chk_auth_email_outbox_next_attempt
        CHECK ((status IN ('PENDING', 'FAILED')) = (next_attempt_at IS NOT NULL)),
    CONSTRAINT chk_auth_email_outbox_deadline
        CHECK (deadline > created_at)
);

-- Both scheduler lanes: WHERE status = ? AND next_attempt_at <= ? ORDER BY next_attempt_at.
CREATE INDEX idx_auth_email_outbox_due
    ON auth_email_outbox (status, next_attempt_at)
    WHERE status IN ('PENDING', 'FAILED');

-- The latest request of a user and type (resend-invite, forgot-password, the
-- operator admin reconciler) and "is there a newer one" (the scheduler).
CREATE INDEX idx_auth_email_outbox_user_type_created
    ON auth_email_outbox (user_id, email_type, created_at);

-- Retention purge of terminal rows.
CREATE INDEX idx_auth_email_outbox_terminal_created
    ON auth_email_outbox (created_at)
    WHERE status IN ('SENT', 'PERMANENTLY_FAILED', 'SUPERSEDED');

COMMENT ON TABLE auth_email_outbox
    IS 'Intent to send an auth email (invite, password reset); the token is created '
       'when the email is sent and only its hash is stored, in auth_tokens (backlog #0-52).';
COMMENT ON COLUMN auth_email_outbox.deadline
    IS 'Until when the request is worth sending: created_at + the token lifetime.';
COMMENT ON COLUMN auth_email_outbox.next_attempt_at
    IS 'When the scheduler may next try this entry; set exactly while PENDING or FAILED.';
