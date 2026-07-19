-- Outbox table for invite emails.
--
-- Design rationale: Outbox Pattern
--
-- Previously UserService.createUser() returned the raw invite token in the
-- API response — the admin had to manually forward it to the invited user
-- via Slack, email, or another channel. This was insecure (token exposed
-- in HTTP logs and browser devtools) and unscalable.
--
-- Now the consumer (UserService) writes a PENDING entry here and returns
-- a response WITHOUT the token. InviteEmailScheduler picks up PENDING
-- entries and sends the email with the token link directly to the user.
--
-- raw_token security window:
-- The raw token is stored here in plaintext for the duration between user
-- creation and email dispatch (typically < 30 seconds). After the email
-- is successfully sent, raw_token is NULLed — only token_id (FK to
-- auth_tokens.id which stores the SHA-256 hash) is retained for audit.
--
-- If the database is compromised BEFORE email dispatch, the attacker could
-- extract raw_token. This window is accepted as a pragmatic trade-off:
-- it is significantly safer than the previous approach of returning the
-- raw token in the HTTP response (which exposed it to logs, proxies, and
-- browser devtools permanently).
--
-- After dispatch, the security properties of the original design are
-- fully restored: only the SHA-256 hash is in the database.

CREATE TABLE invite_email_outbox
(
    id            UUID         NOT NULL,
    user_id       UUID         NOT NULL
        REFERENCES users (id) ON DELETE CASCADE,
    email         VARCHAR(255) NOT NULL,
    token_id      UUID         NOT NULL
        REFERENCES auth_tokens (id) ON DELETE CASCADE,

    -- Raw token stored temporarily — NULLed after successful send.
    -- See design rationale above.
    raw_token     TEXT,

    status        VARCHAR(30)  NOT NULL DEFAULT 'PENDING'
        CONSTRAINT chk_invite_email_outbox_status
            CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'PERMANENTLY_FAILED')),

    retry_count   INT          NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    sent_at       TIMESTAMPTZ,

    CONSTRAINT pk_invite_email_outbox PRIMARY KEY (id)
);

-- Scheduler query: find PENDING entries older than the pending threshold.
CREATE INDEX idx_invite_email_outbox_status_created
    ON invite_email_outbox (status, created_at)
    WHERE status = 'PENDING';

-- Idempotency: one outbox entry per user (prevent duplicate emails on retry).
CREATE UNIQUE INDEX uq_invite_email_outbox_user
    ON invite_email_outbox (user_id)
    WHERE status IN ('PENDING', 'FAILED');

COMMENT ON TABLE invite_email_outbox
    IS 'Outbox for invite emails. UserService writes PENDING entries; '
       'InviteEmailScheduler sends emails and marks SENT/FAILED.';

COMMENT ON COLUMN invite_email_outbox.raw_token
    IS 'Raw (unhashed) invite token — stored temporarily until email is sent, '
       'then NULLed. Only the hash in auth_tokens is retained after dispatch.';

-- ShedLock table — prevents InviteEmailScheduler from running concurrently
-- across multiple auth-service instances.
CREATE TABLE IF NOT EXISTS shedlock
(
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);