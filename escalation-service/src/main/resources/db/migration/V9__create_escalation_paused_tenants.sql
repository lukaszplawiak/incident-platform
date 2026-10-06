-- Suspended tenants whose escalations this service holds back (backlog #0-82,
-- step 2b). A tenant suspended by a platform operator, in either mode, cannot
-- acknowledge an incident (no sign-in when FULL, no write when READ_ONLY), so
-- its escalation timers must not run on: EscalationScheduler's query leaves
-- this table's tenants out, and when the tenant is resumed every PENDING task
-- is moved on by the time it was held (EscalationPausableWork), so it gets the
-- time it had left, not an escalation the moment the tenant is back.
--
-- One writer: PausedTenantsSync (shared), under its ShedLock lock, from
-- auth-service's answer for the tenants with PENDING tasks. paused_at is
-- when the tenant was suspended: auth-service's suspended_at, the same
-- database's clock (when this service saw the suspension only if auth-service
-- did not say), so a late sync does not shorten the pause; kept when the
-- suspension changes mode. Same shape in every service that pauses
-- (notification-, postmortem-service), one table each, named by tenant-pause.table
-- (startup fails unless it is the table the service's queries name).

CREATE TABLE escalation_paused_tenants (
    tenant_id VARCHAR(63) NOT NULL,
    access    VARCHAR(16) NOT NULL,
    paused_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_escalation_paused_tenants PRIMARY KEY (tenant_id),
    -- The platform's one tenant id format (backlog #0-92), as on every table
    -- of this service with a tenant_id.
    CONSTRAINT chk_escalation_paused_tenants_tenant_id_slug
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$'),
    -- What auth-service says the tenant may do; a tenant with full access is
    -- never here.
    CONSTRAINT chk_escalation_paused_tenants_access
        CHECK (access IN ('READ_ONLY', 'NONE'))
);

COMMENT ON TABLE escalation_paused_tenants
    IS 'Suspended tenants whose escalations are held back, and since when (backlog #0-82)';
