-- Renames invite_email_outbox → auth_email_outbox and adds email_type column.
--
-- Motivation: the outbox now serves two auth email flows:
--   INVITE        — new user onboarding (existing flow)
--   PASSWORD_RESET — self-service password recovery (new flow)
--
-- Renaming the table makes the domain intent clear — this is a generic
-- auth email outbox, not specifically an invite email outbox.
--
-- email_type column added with DEFAULT 'INVITE' so all existing rows
-- are correctly classified without a data migration.

ALTER TABLE invite_email_outbox
    RENAME TO auth_email_outbox;

ALTER TABLE auth_email_outbox
    ADD COLUMN email_type VARCHAR(30) NOT NULL DEFAULT 'INVITE'
        CONSTRAINT chk_auth_email_outbox_type
            CHECK (email_type IN ('INVITE', 'PASSWORD_RESET'));

-- Rename indexes to match new table name
ALTER INDEX pk_invite_email_outbox
    RENAME TO pk_auth_email_outbox;

ALTER INDEX idx_invite_email_outbox_status_created
    RENAME TO idx_auth_email_outbox_status_created;

ALTER INDEX uq_invite_email_outbox_user
    RENAME TO uq_invite_email_outbox_user_pending;

-- The unique constraint on (user_id) for PENDING/FAILED only makes sense
-- for INVITE — a user can have both a pending INVITE and a pending
-- PASSWORD_RESET at the same time. Drop the old constraint and replace
-- with a per-type constraint.
DROP INDEX IF EXISTS uq_invite_email_outbox_user_pending;

CREATE UNIQUE INDEX uq_auth_email_outbox_user_type_active
    ON auth_email_outbox (user_id, email_type)
    WHERE status IN ('PENDING', 'FAILED');

-- Add index for email_type to support scheduler query filtering by type
CREATE INDEX idx_auth_email_outbox_type_status
    ON auth_email_outbox (email_type, status, created_at)
    WHERE status = 'PENDING';

COMMENT ON TABLE auth_email_outbox
    IS 'Outbox for auth-domain transactional emails (invite, password reset). '
       'Writers create PENDING entries; AuthEmailScheduler dispatches them.';

COMMENT ON COLUMN auth_email_outbox.email_type
    IS 'INVITE: new user onboarding. PASSWORD_RESET: self-service password recovery.';