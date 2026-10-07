-- Validates V31's foreign keys to tenants on the rows that existed before them
-- (backlog #0-82). VALIDATE CONSTRAINT reads each table under SHARE UPDATE
-- EXCLUSIVE, which lets reads and writes go on, and tenants under ROW SHARE;
-- a separate migration, so a separate transaction from V31's locks. V31 gave
-- every existing tenant id a row and its keys refuse a new row without one, so
-- none of these should fail. If one does, a row without its tenant got in some
-- other way (by hand, or a path not covered by V31's keys): the error names
-- the constraint and the table. On Postgres the failed migration rolls back
-- whole and leaves no history row, and auth-service does not start. Find the
-- tenant ids without a row in that table, give each its row (as incident_app:
-- INSERT INTO tenants (tenant_id, display_name) VALUES ('<id>', '<id>')
-- ON CONFLICT DO NOTHING), and start the service again: V32 runs from the top
-- (validating an already valid constraint is a no-op).

ALTER TABLE users VALIDATE CONSTRAINT fk_users_tenant;
ALTER TABLE user_roles VALIDATE CONSTRAINT fk_user_roles_tenant;
ALTER TABLE teams VALIDATE CONSTRAINT fk_teams_tenant;
ALTER TABLE tenant_settings VALIDATE CONSTRAINT fk_tenant_settings_tenant;
ALTER TABLE auth_tokens VALIDATE CONSTRAINT fk_auth_tokens_tenant;
ALTER TABLE api_keys VALIDATE CONSTRAINT fk_api_keys_tenant;
ALTER TABLE integrations VALIDATE CONSTRAINT fk_integrations_tenant;
ALTER TABLE slack_workspaces VALIDATE CONSTRAINT fk_slack_workspaces_tenant;
ALTER TABLE mfa_recovery_requests VALIDATE CONSTRAINT fk_mfa_recovery_requests_tenant;
