-- The "an API key was created with your account" notice (backlog #0-89).
--
-- Creating an API key emails the account through the auth email outbox: a
-- personal key its owner, a tenant key the admin who created it, so a key made
-- with a stolen password is noticed. Like the MFA notices it carries no token.
-- One email per key, never merged or superseded by a later one (review: merging
-- would let a key made right after the owner's own hide behind its notice), so
-- the row names its key (api_key_id): the email shows the key's id, which the
-- owner can match against the key list (not its prefix, part of the secret).
-- ON DELETE SET NULL: keys are revoked, not deleted, and a deleted key only drops
-- the id from the email. Indexed, so such a delete does not scan the outbox.
--
-- The type constraint is widened, as in V23 and V24; the existing rows all
-- satisfy the wider list. Dropping and re-adding a CHECK takes a brief ACCESS
-- EXCLUSIVE lock and re-validates the rows of this small, purged table.
--
-- Rolling deploy: a replica of the previous release cannot map API_KEY_CREATED,
-- so if its scheduler run loads such a row it fails that run; the row stays
-- PENDING and a replica of the new release sends it once the old ones are gone
-- (as V23, V24). The failed run may also delay the other rows of its batch by
-- one scheduler interval, until a new-release replica takes them.

ALTER TABLE auth_email_outbox
    ADD COLUMN api_key_id UUID REFERENCES api_keys (id) ON DELETE SET NULL;

ALTER TABLE auth_email_outbox DROP CONSTRAINT chk_auth_email_outbox_type;
ALTER TABLE auth_email_outbox ADD CONSTRAINT chk_auth_email_outbox_type
    CHECK (email_type IN ('INVITE', 'PASSWORD_RESET', 'MFA_ENABLED', 'MFA_DISABLED', 'MFA_RESET',
                          'API_KEY_CREATED'));

COMMENT ON TABLE auth_email_outbox
    IS 'Intent to send an auth email: invite and password reset carry a token, created '
       'when the email is sent and stored only as a hash in auth_tokens (backlog #0-52); '
       'MFA_ENABLED / MFA_DISABLED (backlog #0-83), MFA_RESET (backlog #0-88) and '
       'API_KEY_CREATED (backlog #0-89) notices carry none.';

CREATE INDEX idx_auth_email_outbox_api_key
    ON auth_email_outbox (api_key_id)
    WHERE api_key_id IS NOT NULL;

COMMENT ON COLUMN auth_email_outbox.api_key_id
    IS 'The key an API_KEY_CREATED notice is about (backlog #0-89); NULL for every other type.';
