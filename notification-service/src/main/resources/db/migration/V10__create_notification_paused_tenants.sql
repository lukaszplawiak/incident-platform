-- Suspended tenants whose notifications this service holds back (backlog
-- #0-82, step 2b). A suspended tenant's incidents are not announced to its
-- members (email, SMS, Slack) while a platform operator holds it, in either
-- mode: NotificationScheduler's query leaves this table's tenants out, so their
-- PENDING entries wait, keep their order, and starve no other tenant. The
-- consumer goes on writing entries meanwhile; on resumption they are all sent
-- (decided: nothing is dropped for its age, the tenant's alerts were refused
-- at intake during the suspension, so what waits is still true).
--
-- One writer: PausedTenantsSync (shared), under its ShedLock lock, from
-- auth-service's answer for the tenants with PENDING entries. paused_at is
-- when the tenant was suspended: auth-service's suspended_at, the same
-- database's clock (when this service saw the suspension only if auth-service
-- did not say), so a late sync does not shorten the pause; kept when the
-- suspension changes mode. Same shape in every service that pauses
-- (escalation-, postmortem-service), one table each, named by tenant-pause.table
-- (startup fails unless it is the table the service's queries name).

CREATE TABLE notification_paused_tenants (
    tenant_id VARCHAR(63) NOT NULL,
    access    VARCHAR(16) NOT NULL,
    paused_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_notification_paused_tenants PRIMARY KEY (tenant_id),
    -- The platform's one tenant id format (backlog #0-92), as on every table
    -- of this service with a tenant_id.
    CONSTRAINT chk_notification_paused_tenants_tenant_id_slug
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$'),
    -- What auth-service says the tenant may do; a tenant with full access is
    -- never here.
    CONSTRAINT chk_notification_paused_tenants_access
        CHECK (access IN ('READ_ONLY', 'NONE'))
);

COMMENT ON TABLE notification_paused_tenants
    IS 'Suspended tenants whose notifications are held back, and since when (backlog #0-82)';
