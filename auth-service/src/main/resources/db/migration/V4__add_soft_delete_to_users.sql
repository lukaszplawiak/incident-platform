-- Soft delete for users table.
--
-- Design decisions:
--
-- deleted_at timestamp rather than a boolean deleted flag.
-- The timestamp is more informative (when was the user deleted?)
-- and enables time-based queries (e.g. "deleted in the last 30 days").
-- A boolean would require a separate deleted_at column anyway for auditing.
--
-- Soft delete rather than hard delete.
-- Hard delete would cascade to user_roles and auth_tokens (ON DELETE CASCADE),
-- but would also orphan audit trail records in incident-service that reference
-- the user's UUID. Soft delete preserves the full audit trail — the user record
-- remains in the database as a historical anchor, but is invisible to all
-- application queries that filter WHERE deleted_at IS NULL.
--
-- Partial unique index on (email, tenant_id) WHERE deleted_at IS NULL.
-- The existing UNIQUE constraint on (email, tenant_id) is dropped and replaced
-- with a partial unique index. This allows the same email to be re-invited
-- after a soft delete — a common scenario when a contractor leaves and
-- returns, or when a user is deleted by mistake and needs to be re-created.
-- Two deleted users with the same email are allowed (no constraint on
-- deleted records) — only one active/non-deleted user per email per tenant.
--
-- No data migration required — all existing users get deleted_at = NULL
-- (not deleted) by default.

ALTER TABLE users
    ADD COLUMN deleted_at TIMESTAMPTZ;

-- Drop the old unconditional unique constraint on email + tenant
ALTER TABLE users
DROP CONSTRAINT uq_users_email_tenant;

-- Replace with a partial unique index — only enforced for non-deleted users.
-- Two rows with the same (email, tenant_id) are allowed if at least one
-- has deleted_at IS NOT NULL.
CREATE UNIQUE INDEX uq_users_email_tenant_active
    ON users (email, tenant_id)
    WHERE deleted_at IS NULL;

-- Index for soft-delete filtering — most queries will include
-- WHERE deleted_at IS NULL, this makes them efficient.
CREATE INDEX idx_users_deleted_at
    ON users (tenant_id, deleted_at)
    WHERE deleted_at IS NOT NULL;

COMMENT ON COLUMN users.deleted_at
    IS 'Soft delete timestamp. NULL = active or deactivated. Non-null = deleted. '
       'Deleted users are invisible to all application queries but preserved '
       'for audit trail integrity.';