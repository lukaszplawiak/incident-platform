-- Security notifications through the auth email outbox (backlog #0-83).
--
-- Enabling or disabling MFA now emails the account's address. Without it, an
-- attacker who has only a user's password could enrol a second factor of
-- their own and look exactly like the owner; the email tells the real owner
-- while the platform API's grace period (platform.mfa.enrolment-grace) still
-- refuses the new factor. These emails carry no token: the outbox row is the
-- intent to send, as for invites and resets (V19), and the scheduler sends it
-- with the same retries until its deadline.
--
-- The type constraint is widened; the existing rows all satisfy the wider
-- list. Dropping and re-adding a CHECK takes a brief ACCESS EXCLUSIVE lock and
-- re-validates the rows of this small, purged table.
--
-- Rolling deploy: a replica of the previous release cannot map the new types,
-- so if its scheduler run loads an MFA_* row it fails that run; the row stays
-- PENDING and a replica of the new release sends it once the old ones are
-- gone. No row is lost; a notice may go out a few minutes late.
--
-- users.mfa_enabled_notice_sent_at: when the MFA_ENABLED notice of the
-- account's current factor was sent. The platform API counts the grace period
-- from it, not from the enrolment, so the owner always has the whole period to
-- react, also when SMTP delayed the notice. It lives on the user, not only on
-- the outbox row, because the outbox purges sent rows after
-- invite.email.retention (30 days): a fact the platform API depends on must
-- outlive that (found in review). Set by the scheduler when it marks the
-- current enrolment's notice SENT; cleared by enabling or disabling MFA.
--
-- Backfill: factors enabled before this migration never had a notice. Those
-- enabled at least 24 hours ago (the default platform.mfa.enrolment-grace) are
-- taken over as established (notice time = enrolment time), the usual way to
-- carry existing data across a new rule: otherwise every such user would lose
-- MFA on their next password reset (a reset removes a factor that is not
-- established), and every operator would be refused by the platform API until
-- re-enrolling (found in review). A factor enabled within the last 24 hours
-- is not: a password thief who enrolled one shortly before the deploy would
-- otherwise skip both the grace period and the warning email (found in
-- review). Its owner disables and enables MFA again to get the notice; until
-- then the platform API refuses it and a password reset removes it, the safe
-- side. The cutoff is fixed here, not read from configuration, which a
-- migration cannot see.
--
-- Not for the platform-operator tenant, whatever the factor's age (found in
-- review): the platform API is the one cross-tenant capability, and a factor
-- of an operator admin that predates this migration may have been enrolled by
-- someone with the password alone, unchallenged. An operator admin disables
-- and enables MFA again once after this deploy, gets the notice, and the
-- platform API accepts the factor after the grace period (operator guide).
-- The literal is ReservedTenants.PLATFORM_OPERATOR. version + 1, as @Version
-- would. A row with
-- mfa_enabled but no mfa_enabled_at is not backfilled either: no code path
-- writes one (User.enableMfa sets both).
ALTER TABLE auth_email_outbox DROP CONSTRAINT chk_auth_email_outbox_type;
ALTER TABLE auth_email_outbox ADD CONSTRAINT chk_auth_email_outbox_type
    CHECK (email_type IN ('INVITE', 'PASSWORD_RESET', 'MFA_ENABLED', 'MFA_DISABLED'));

COMMENT ON TABLE auth_email_outbox
    IS 'Intent to send an auth email: invite and password reset carry a token, created '
       'when the email is sent and stored only as a hash in auth_tokens (backlog #0-52); '
       'MFA_ENABLED / MFA_DISABLED notices carry none (backlog #0-83).';

ALTER TABLE users ADD COLUMN mfa_enabled_notice_sent_at TIMESTAMPTZ;

UPDATE users
SET mfa_enabled_notice_sent_at = mfa_enabled_at, version = version + 1
WHERE mfa_enabled
  AND mfa_enabled_at IS NOT NULL
  AND mfa_enabled_at <= now() - INTERVAL '24 hours'
  AND tenant_id <> 'platform-operator';
