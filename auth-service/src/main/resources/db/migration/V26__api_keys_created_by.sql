-- Who created an API key, and from which login session (backlog #0-89).
--
-- A tenant key (an integration's included) has no owner, so until now nothing
-- recorded who made it: after an admin's account was taken over, nobody could
-- tell which keys the intruder had created. These columns let the key list
-- show and filter by creator, an admin revoke every key a user created since a
-- given time (POST /api/v1/api-keys/revoke-created-by), and the creation limit
-- count the keys a user created in the last hour, revoked ones included.
--
-- Both nullable: rows from before this migration have no session to record,
-- and a tenant key's creator is unknown for them. A personal key was always
-- created by its owner, so created_by_user_id is filled from owner_user_id.
-- ON DELETE SET NULL, like owner_user_id: users are archived, not deleted,
-- and a key must not disappear with a deleted row.
--
-- Adding nullable columns without a default rewrites nothing; the backfill
-- updates only personal keys, a few per user (at most 10 active each).
-- Rolling deploy: a replica of the previous release neither reads nor writes
-- these columns, so keys it creates have no recorded creator (null), which the
-- revoke action treats as "unknown", never as a match.

ALTER TABLE api_keys
    ADD COLUMN created_by_user_id UUID REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN created_in_session_id UUID;

UPDATE api_keys
SET created_by_user_id = owner_user_id
WHERE key_type = 'PERSONAL'
  AND created_by_user_id IS NULL;

-- Not partial on revoked_at: the creation limit counts revoked keys too (a
-- create-and-revoke loop must not escape it); the other queries filter
-- revoked_at on the few rows of one creator.
CREATE INDEX idx_api_keys_created_by
    ON api_keys (tenant_id, created_by_user_id, created_at)
    WHERE created_by_user_id IS NOT NULL;

COMMENT ON COLUMN api_keys.created_by_user_id
    IS 'User who created the key (backlog #0-89): the owner of a personal key, the admin '
       'who created a tenant or integration key. NULL for tenant keys created before V26.';
COMMENT ON COLUMN api_keys.created_in_session_id
    IS 'Login session (auth_tokens.session_id) the key was created from, to tell which '
       'keys a taken-over session made (backlog #0-89). NULL before V26.';
