-- Every tenant id is a slug (backlog #0-92): 3-63 characters of a-z, 0-9 and
-- '-', starting and ending with a letter or digit (a DNS label), the platform's
-- one format (TenantIds in shared).
--
-- Reverses V21's "backfilled ones are kept as they are": until #0-92 only a
-- tenant created through the platform API (#0-80) had to be a slug, and a
-- tenant id elsewhere was any string. The platform holds no data to keep (it
-- is in development), so the rule is now a constraint on every auth-service
-- table with a tenant_id, not only where tenants are recorded: every service
-- does the same on its own tables. The code checks tenants where they enter
-- (tokens, headers, Kafka records), but a row is also read back by schedulers
-- that set TenantContext from it, which refuses an invalid id; a constraint
-- keeps such a row from existing at all, whichever path writes it (found in
-- review: one such row stopped a whole scheduler batch).
--
-- A database with a tenant id that does not match stops this migration with
-- a check violation. To find one first:
--   SELECT DISTINCT tenant_id FROM users
--   WHERE tenant_id !~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$';
-- (the same for each table below); rename or remove that tenant, or start from
-- an empty database, and run it again.
--
-- A plain ADD CONSTRAINT validates every row under an ACCESS EXCLUSIVE lock,
-- harmless on these small tables before the first release (as V18). On a
-- populated table it would be ADD CONSTRAINT ... NOT VALID, then VALIDATE
-- CONSTRAINT in its own migration.

ALTER TABLE tenants
    ADD CONSTRAINT chk_tenants_tenant_id_slug
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$');

ALTER TABLE users
    ADD CONSTRAINT chk_users_tenant_id_slug
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$');

ALTER TABLE user_roles
    ADD CONSTRAINT chk_user_roles_tenant_id_slug
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$');

ALTER TABLE teams
    ADD CONSTRAINT chk_teams_tenant_id_slug
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$');

ALTER TABLE tenant_settings
    ADD CONSTRAINT chk_tenant_settings_tenant_id_slug
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$');

ALTER TABLE auth_tokens
    ADD CONSTRAINT chk_auth_tokens_tenant_id_slug
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$');

ALTER TABLE api_keys
    ADD CONSTRAINT chk_api_keys_tenant_id_slug
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$');

ALTER TABLE integrations
    ADD CONSTRAINT chk_integrations_tenant_id_slug
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$');

ALTER TABLE slack_workspaces
    ADD CONSTRAINT chk_slack_workspaces_tenant_id_slug
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$');

ALTER TABLE auth_email_outbox
    ADD CONSTRAINT chk_auth_email_outbox_tenant_id_slug
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$');

ALTER TABLE auth_audit_outbox
    ADD CONSTRAINT chk_auth_audit_outbox_tenant_id_slug
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$');
