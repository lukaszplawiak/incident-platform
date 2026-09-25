-- Allows the two MFA token types in auth_tokens (backlog #0-51).
--
-- Problem: chk_auth_token_type (V2, widened in V6) lists only INVITE,
-- PASSWORD_RESET and REFRESH. AuthToken.Type gained MFA_SESSION (MFA, commit
-- 372ac32) and MFA_SETUP_REQUIRED (efe96a9) without a migration, so every
-- INSERT of those tokens violated the constraint: logging in as a user with
-- MFA enabled, or into a tenant that requires MFA, failed with a 500 on a real
-- database. The service tests mock the repositories and never reached the
-- INSERT. AuthRepositoryIntegrationTest now stores one token of every
-- AuthToken.Type, so a new type without a migration fails CI.
--
-- Drop and re-add in this migration's transaction. Every existing row already
-- has one of the three allowed types, so validating the new, wider constraint
-- cannot fail; auth_tokens is small and the lock is brief, so NOT VALID +
-- VALIDATE CONSTRAINT would add nothing.

ALTER TABLE auth_tokens
    DROP CONSTRAINT chk_auth_token_type;

ALTER TABLE auth_tokens
    ADD CONSTRAINT chk_auth_token_type
        CHECK (type IN ('INVITE', 'PASSWORD_RESET', 'REFRESH',
                        'MFA_SESSION', 'MFA_SETUP_REQUIRED'));

COMMENT ON COLUMN auth_tokens.type
    IS 'INVITE: new user onboarding. PASSWORD_RESET: forgot password flow. '
       'REFRESH: session continuity - rotated on each use, 30-day TTL. '
       'MFA_SESSION: second-factor step after a password login, 5-minute TTL. '
       'MFA_SETUP_REQUIRED: MFA setup when the tenant requires it, 10-minute TTL. '
       'Must match AuthToken.Type (checked by AuthRepositoryIntegrationTest).';
