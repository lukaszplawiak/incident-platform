-- Introduces archiving (replace soft-delete) and GDPR anonymization.
--
-- Semantic change: deleted_at → archived_at
--   "Archived" correctly describes the intent: the record is preserved
--   but hidden from normal queries. "Deleted" is misleading because the
--   record is never physically removed (archiving model, not delete model).
--
-- New column: users.anonymized_at
--   Marks users whose personal data (email, password_hash) has been
--   anonymized for GDPR compliance. Anonymization is irreversible.
--   A non-null anonymized_at means:
--     - email has been replaced with "anonymized-{uuid}@deleted.invalid"
--     - password_hash has been set to NULL
--     - all roles and team memberships have been removed
--     - the user UUID is preserved for referential integrity
--
-- @SQLRestriction update:
--   Both archived_at IS NULL and anonymized_at IS NULL must be checked
--   to exclude archived AND anonymized users from normal queries.
--
-- GDPR note (Data Vault TODO):
--   This approach (anonymize in-place) is pragmatic but not ideal.
--   A cleaner GDPR solution is the Data Vault pattern:
--     users:         id, tenant_id, active (no PII)
--     personal_data: user_id FK, email, password_hash, deleted_at
--   Anonymization = DELETE FROM personal_data WHERE user_id = ?
--   UUID survives, all references intact, zero risk of re-identification.
--   Migration to Data Vault is planned when the system reaches a scale
--   where true GDPR compliance for enterprise customers is required.

-- ── users ─────────────────────────────────────────────────────────────────

ALTER TABLE users
    RENAME COLUMN deleted_at TO archived_at;

ALTER TABLE users
    ADD COLUMN anonymized_at TIMESTAMPTZ;

COMMENT ON COLUMN users.archived_at
    IS 'Archiving timestamp. NULL = active. Set by DELETE /api/v1/users/{id}. '
       'Reversible via POST /api/v1/users/{id}/restore. '
       'Unlike hard-delete, the record and all its references (audit logs, '
       'incident history) are preserved.';

COMMENT ON COLUMN users.anonymized_at
    IS 'GDPR anonymization timestamp. NULL = not anonymized. '
       'When set: email replaced with anonymized alias, password_hash nulled, '
       'roles and team memberships removed. IRREVERSIBLE. '
       'TODO: migrate to Data Vault pattern for cleaner GDPR compliance.';

-- ── teams ──────────────────────────────────────────────────────────────────

ALTER TABLE teams
    RENAME COLUMN deleted_at TO archived_at;

COMMENT ON COLUMN teams.archived_at
    IS 'Archiving timestamp. NULL = active. Reversible via '
       'POST /api/v1/teams/{id}/restore. TeamMember rows are preserved '
       'during archiving to enable full restore.';