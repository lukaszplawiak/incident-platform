-- The "an administrator reset your MFA" notice (backlog #0-88).
--
-- An admin MFA reset (POST /api/v1/users/{id}/mfa-reset) and the break-glass
-- command email the account through the auth email outbox, as a type of their
-- own: until now they would have sent MFA_DISABLED, which reads as the user's
-- own change, so a reset the owner did not ask for would not stand out.
--
-- The type constraint is widened, as in V23; the existing rows all satisfy the
-- wider list. Dropping and re-adding a CHECK takes a brief ACCESS EXCLUSIVE lock
-- and re-validates the rows of this small, purged table.
--
-- Rolling deploy: a replica of the previous release cannot map MFA_RESET, so if
-- its scheduler run loads such a row it fails that run; the row stays PENDING
-- and a replica of the new release sends it once the old ones are gone (as V23).
-- The failed run may also delay the other rows of its batch (invites, resets)
-- by one scheduler interval, until a new-release replica takes them.
--
-- V23's comment that a password reset removes a factor still within the grace
-- period describes #0-83's interim behaviour, which #0-88 removed; V23 itself
-- is left unchanged (a changed checksum fails Flyway's validation).

ALTER TABLE auth_email_outbox DROP CONSTRAINT chk_auth_email_outbox_type;
ALTER TABLE auth_email_outbox ADD CONSTRAINT chk_auth_email_outbox_type
    CHECK (email_type IN ('INVITE', 'PASSWORD_RESET', 'MFA_ENABLED', 'MFA_DISABLED', 'MFA_RESET'));

COMMENT ON TABLE auth_email_outbox
    IS 'Intent to send an auth email: invite and password reset carry a token, created '
       'when the email is sent and stored only as a hash in auth_tokens (backlog #0-52); '
       'MFA_ENABLED / MFA_DISABLED (backlog #0-83) and MFA_RESET (backlog #0-88) notices carry none.';
