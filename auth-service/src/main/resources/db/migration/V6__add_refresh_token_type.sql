-- Adds REFRESH token type to auth_tokens table.
--
-- Design: reuse existing auth_tokens infrastructure for refresh tokens.
--
-- Refresh tokens share the same lifecycle as INVITE and PASSWORD_RESET:
--   1. Generated as SecureRandom bytes, only SHA-256 hash stored
--   2. Single-use with rotation — consuming a refresh token immediately
--      marks it used (used_at IS NOT NULL) and issues a new one
--   3. TTL enforced via expires_at (default: 30 days)
--   4. Revoked implicitly by logout (markUsed()) or explicitly by admin
--
-- Rotation security property:
--   If an attacker steals a refresh token and uses it, the legitimate
--   user's next refresh attempt will fail (token already used), alerting
--   them to the compromise. This is the same security model used by
--   GitHub, Auth0, and Google OAuth2.
--
-- Why not a separate refresh_tokens table:
--   auth_tokens already provides: SHA-256 hashing, single-use semantics,
--   TTL via expires_at, user FK with CASCADE, cleanup via deleteExpiredAndUsed().
--   Adding a type is one DDL statement vs. a full new table with indexes.

ALTER TABLE auth_tokens
DROP CONSTRAINT chk_auth_token_type;

ALTER TABLE auth_tokens
    ADD CONSTRAINT chk_auth_token_type
        CHECK (type IN ('INVITE', 'PASSWORD_RESET', 'REFRESH'));

-- Index for refresh token lookup by user — used to invalidate all
-- active refresh tokens on logout (logout all sessions).
CREATE INDEX idx_auth_tokens_user_type_refresh
    ON auth_tokens (user_id, type)
    WHERE type = 'REFRESH'
      AND used_at IS NULL;

COMMENT ON COLUMN auth_tokens.type
    IS 'INVITE: new user onboarding. PASSWORD_RESET: forgot password flow. '
       'REFRESH: session continuity — rotated on each use, 30-day TTL.';