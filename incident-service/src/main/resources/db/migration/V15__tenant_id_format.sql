-- Every tenant id is a slug (backlog #0-92): 3-63 characters of a-z, 0-9 and
-- '-', starting and ending with a letter or digit (a DNS label), the platform's
-- one format (TenantIds in shared). Same constraint as auth-service V28, on
-- every table of this service with a tenant_id.
--
-- The code checks a tenant where it enters (tokens, headers, Kafka records),
-- but a row is also read back by schedulers that set TenantContext from it,
-- which refuses an invalid id; the constraint keeps such a row from existing
-- at all, whichever path writes it (found in review: one such row stopped a
-- whole scheduler batch). The platform holds no data to keep (it is in
-- development): a database with a tenant id that does not match stops this
-- migration with a check violation; remove that row, or start from an empty
-- database, and run it again. To find one first, per table:
--   SELECT DISTINCT tenant_id FROM <table>
--   WHERE tenant_id !~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$';
--
-- A plain ADD CONSTRAINT validates every row under an ACCESS EXCLUSIVE lock,
-- harmless before the first release. On a populated table it would be
-- ADD CONSTRAINT ... NOT VALID, then VALIDATE CONSTRAINT in its own migration.

ALTER TABLE incidents
    ADD CONSTRAINT chk_incidents_tenant_id_slug
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$');

ALTER TABLE incident_history
    ADD CONSTRAINT chk_incident_history_tenant_id_slug
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$');

ALTER TABLE audit_events
    ADD CONSTRAINT chk_audit_events_tenant_id_slug
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$');

ALTER TABLE incident_event_outbox
    ADD CONSTRAINT chk_incident_event_outbox_tenant_id_slug
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$');

ALTER TABLE incident_audit_outbox
    ADD CONSTRAINT chk_incident_audit_outbox_tenant_id_slug
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$');
