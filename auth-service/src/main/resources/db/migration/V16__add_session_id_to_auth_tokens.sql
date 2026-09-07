-- Adds session-aware invalidation support (backlog: architecture item
-- found while analyzing #83 — "invalidate all sessions except current").
--
-- Previously, an access token (JWT) and a refresh token (this table's
-- AuthToken.Type.REFRESH rows) issued at the same login had no shared
-- identifier linking them together. This meant no code path could ever
-- answer "which refresh token belongs to the same login as this access
-- token I'm holding right now" — LogoutService could precisely revoke
-- the current access token (by its own jti, via TokenRevocationService/
-- Redis) but had no way to precisely revoke only the matching refresh
-- token, so it invalidated every refresh token for the user instead.
-- The same missing link blocked the reverse operation PasswordService
-- .changePassword() needs: invalidate every OTHER session's refresh
-- token while leaving the current one alone.
--
-- session_id is a random UUID generated once per login (AuthService
-- .login(), MfaService's post-MFA token issuance) and carried both as
-- a new JWT claim (see JwtUtils) and here, on the paired refresh
-- token. AuthTokenService.rotateRefreshToken() carries the same
-- session_id forward onto the rotated token, since rotation continues
-- the same logical session rather than starting a new one.
--
-- Nullable, not required at the column level: only REFRESH tokens have
-- a session in this sense — INVITE, PASSWORD_RESET, MFA_SESSION, and
-- MFA_SETUP_REQUIRED tokens are issued outside of (or before) an
-- established login session and never populate this column.
--
-- No backfill needed for existing rows — this project has no
-- production data yet.
ALTER TABLE auth_tokens ADD COLUMN session_id UUID;

-- Supports AuthTokenService.invalidateRefreshTokenForSession's and
-- .invalidateAllRefreshTokensExceptSession's WHERE clauses, both
-- filtering by (user_id, session_id) together.
CREATE INDEX idx_auth_tokens_user_session
    ON auth_tokens (user_id, session_id);