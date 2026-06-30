-- Auth tokens table — shared mechanism for invite and password reset flows.
--
-- Design decisions:
--
-- Single table for multiple token types (type column with CHECK constraint).
-- Both invite and password reset follow the same lifecycle:
--   1. Admin/system generates token → stored here with TTL
--   2. User receives token via email (or API response for now)
--   3. User submits token → validated, marked used, action performed
--   4. Expired or used tokens are rejected
--
-- type = 'INVITE':
--   Created when admin calls POST /api/v1/users.
--   User calls POST /api/v1/auth/accept-invite with token to set password.
--
-- type = 'PASSWORD_RESET':
--   Created when user calls POST /api/v1/auth/forgot-password.
--   User calls POST /api/v1/auth/reset-password with token.
--   (Requires email infrastructure — planned, see backlog.)
--
-- used_at is NULL until the token is consumed. Tokens are single-use:
-- once used_at is set, the token is rejected on subsequent calls.
-- expires_at is set at creation time (INVITE: 72h, PASSWORD_RESET: 15min).
--
-- user_id references users(id) ON DELETE CASCADE:
-- if a user is deleted, their pending tokens are removed automatically.
-- This prevents orphaned tokens from being used after account deletion.

CREATE TABLE auth_tokens
(
    id         UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    tenant_id  VARCHAR(255) NOT NULL,
    token_hash VARCHAR(255) NOT NULL,
    type       VARCHAR(50)  NOT NULL
        CONSTRAINT chk_auth_token_type
            CHECK (type IN ('INVITE', 'PASSWORD_RESET')),
    expires_at TIMESTAMPTZ  NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_auth_tokens
        PRIMARY KEY (id),

    -- Token hash must be globally unique — prevents hash collisions
    -- from allowing cross-user token reuse (extremely unlikely with
    -- SecureRandom, but enforced defensively).
    CONSTRAINT uq_auth_tokens_hash
        UNIQUE (token_hash)
);

CREATE INDEX idx_auth_tokens_user
    ON auth_tokens (user_id);

CREATE INDEX idx_auth_tokens_hash
    ON auth_tokens (token_hash)
    WHERE used_at IS NULL;

CREATE INDEX idx_auth_tokens_expires
    ON auth_tokens (expires_at)
    WHERE used_at IS NULL;

COMMENT ON TABLE auth_tokens
    IS 'Single-use tokens for invite and password reset flows. Shared schema, type-discriminated.';
COMMENT ON COLUMN auth_tokens.token_hash
    IS 'SHA-256 hash of the raw token. Raw token is only ever returned in the API response or sent by email — never stored.';
COMMENT ON COLUMN auth_tokens.used_at
    IS 'Set when token is consumed. NULL = not yet used. Non-null = rejected on reuse.';
COMMENT ON COLUMN auth_tokens.expires_at
    IS 'INVITE: 72 hours. PASSWORD_RESET: 15 minutes. Set at creation time.';