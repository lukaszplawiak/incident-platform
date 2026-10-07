-- Every row of a tenant's data belongs to a recorded tenant (backlog #0-82).
--
-- Until now tenants (V21) and the rows naming a tenant were joined by
-- convention only: provisioning and the operator's bootstrap insert the tenants
-- row before the first user, and V21 backfilled it from users. A tenant id with
-- data but no row had full access and could not be suspended (suspend finds no
-- row); TenantAccessService counted it (alert PlatformTenantStatusRowMissing)
-- as the gap step 1 of #0-82 left open. These foreign keys close it: no path,
-- present or future, can write a tenant's data before its row exists.
--
-- Covered: every auth-service table holding a tenant's data. Not covered, on
-- purpose: auth_email_outbox and auth_audit_outbox, queues of work about rows
-- that are themselves covered here, whose events must never be refused for
-- their tenant. The other six services keep tenant_id as a plain string on
-- their own tables: a foreign key across services would tie their schemas to
-- auth-service's (backlog #0-85 is about ids they hold that auth-service does
-- not know). AuthRepositoryIntegrationTest checks that every table here with a
-- tenant_id, except the two outboxes, has a validated foreign key to tenants.
--
-- ON DELETE RESTRICT: a tenants row is never deleted. Offboarding (backlog
-- #0-101) ends in OFFBOARDED, a tombstone that keeps the id taken, and the
-- restriction keeps the row from going while any of its data is left.
--
-- Two steps, the form V28 named for a populated table: here the constraints are
-- added NOT VALID (checked for every new or changed row at once, existing rows
-- not read), and V32 validates the existing rows under a lock that lets reads
-- and writes go on.
--
-- Locking: the migration first takes SHARE ROW EXCLUSIVE on tenants and the
-- nine tables, the lock each ADD CONSTRAINT would take anyway, and holds them
-- until it commits. Taken before the backfill (second review): otherwise an
-- old pod could commit a row for an unrecorded tenant after the backfill read
-- its table and before the ALTER locked it; NOT VALID would not check that
-- row, and V32 would fail on it. With the locks first, nothing writes to these
-- tables between the backfill and the constraints. Reads go on (sign-in's
-- FOR SHARE on a tenants row included: ROW SHARE does not conflict); writes to
-- the nine tables, and suspend/resume, wait for the whole migration: one read
-- of each table plus nine catalog changes, well under a second on
-- auth-service's data.
--
-- The locks are taken one table after another, in the order below. A request
-- of an old pod that already wrote one of these tables (users) and then
-- writes another that the migration locked first can deadlock with it;
-- Postgres aborts one of the two. If it is the request, auth-service answers
-- 503 + Retry-After (TenantStatusBusyHandler's lost-lock answer); if it is
-- the migration, it rolls back whole and runs again on the next start. SET
-- LOCAL lock_timeout, as notification-service's V5 explains, bounds how long
-- each lock is waited for, not the run: if an old pod's long transaction holds
-- a table for more than 5 s, the migration fails the same way, instead of
-- every writer queueing behind a waiting lock.

SET LOCAL lock_timeout = '5s';

LOCK TABLE tenants, users, user_roles, teams, tenant_settings, auth_tokens, api_keys, integrations,
    slack_workspaces, mfa_recovery_requests IN SHARE ROW EXCLUSIVE MODE;

-- A tenant id with data in one of these tables but no row (none should exist:
-- see above) gets one, ACTIVE, the id as its display name, as V21 did for
-- users: refusing the migration would stop a deployment over a gap that the
-- row closes, and the row is what makes such a tenant suspendable. The slug
-- CHECK of V28 already holds for every id here, so an id is safe in a log line.
--
-- Not silently (review): each id adopted here is named in a WARNING, which
-- Flyway writes to auth-service's log ("DB: ..."), a reserved one
-- (ReservedTenants: platform-operator, system) flagged as such. A reserved id
-- is adopted like any other rather than refused: refusing would stop the
-- deployment, and being reserved grants nothing (ReservedTenants is never an
-- authorization decision); recorded, it can at least be suspended. Rows
-- adopted here, like V21's, have no first_admin_email and no created_by, which
-- tells them apart from provisioned tenants.
DO $$
DECLARE
    adopted RECORD;
BEGIN
    FOR adopted IN
        SELECT existing.tenant_id
        FROM (SELECT tenant_id FROM users
              UNION SELECT tenant_id FROM user_roles
              UNION SELECT tenant_id FROM teams
              UNION SELECT tenant_id FROM tenant_settings
              UNION SELECT tenant_id FROM auth_tokens
              UNION SELECT tenant_id FROM api_keys
              UNION SELECT tenant_id FROM integrations
              UNION SELECT tenant_id FROM slack_workspaces
              UNION SELECT tenant_id FROM mfa_recovery_requests) AS existing
        WHERE NOT EXISTS (SELECT 1 FROM tenants t WHERE t.tenant_id = existing.tenant_id)
        ORDER BY existing.tenant_id
    LOOP
        RAISE WARNING 'V31: tenant % had data but no tenants row; recorded as ACTIVE%', adopted.tenant_id,
            CASE WHEN adopted.tenant_id IN ('platform-operator', 'system')
                 THEN ' (a reserved tenant id)' ELSE '' END;
    END LOOP;
END
$$;

INSERT INTO tenants (tenant_id, display_name)
SELECT tenant_id, tenant_id
FROM (SELECT tenant_id FROM users
      UNION SELECT tenant_id FROM user_roles
      UNION SELECT tenant_id FROM teams
      UNION SELECT tenant_id FROM tenant_settings
      UNION SELECT tenant_id FROM auth_tokens
      UNION SELECT tenant_id FROM api_keys
      UNION SELECT tenant_id FROM integrations
      UNION SELECT tenant_id FROM slack_workspaces
      UNION SELECT tenant_id FROM mfa_recovery_requests) AS existing
ON CONFLICT (tenant_id) DO NOTHING;

ALTER TABLE users
    ADD CONSTRAINT fk_users_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (tenant_id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE user_roles
    ADD CONSTRAINT fk_user_roles_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (tenant_id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE teams
    ADD CONSTRAINT fk_teams_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (tenant_id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE tenant_settings
    ADD CONSTRAINT fk_tenant_settings_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (tenant_id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE auth_tokens
    ADD CONSTRAINT fk_auth_tokens_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (tenant_id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE api_keys
    ADD CONSTRAINT fk_api_keys_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (tenant_id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE integrations
    ADD CONSTRAINT fk_integrations_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (tenant_id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE slack_workspaces
    ADD CONSTRAINT fk_slack_workspaces_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (tenant_id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE mfa_recovery_requests
    ADD CONSTRAINT fk_mfa_recovery_requests_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (tenant_id) ON DELETE RESTRICT NOT VALID;
