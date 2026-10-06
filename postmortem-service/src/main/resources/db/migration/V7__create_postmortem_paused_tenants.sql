-- Suspended tenants whose postmortems this service does not generate while
-- they are held (backlog #0-82, step 2b): no Gemini call, which costs money, for
-- a tenant a platform operator suspended (for instance over billing), in either
-- mode. PostmortemRetryScheduler's two queries leave this table's tenants out;
-- their GENERATING and FAILED postmortems wait and are generated on
-- resumption, with no retry spent meanwhile.
--
-- One writer: PausedTenantsSync (shared), under its ShedLock lock, from
-- auth-service's answer for the tenants with postmortems to generate. paused_at is
-- when the tenant was suspended: auth-service's suspended_at, the same
-- database's clock (when this service saw the suspension only if auth-service
-- did not say), so a late sync does not shorten the pause; kept when the
-- suspension changes mode. Same shape in every service that pauses
-- (notification-, escalation-service), one table each, named by tenant-pause.table
-- (startup fails unless it is the table the service's queries name).

CREATE TABLE postmortem_paused_tenants (
    tenant_id VARCHAR(63) NOT NULL,
    access    VARCHAR(16) NOT NULL,
    paused_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_postmortem_paused_tenants PRIMARY KEY (tenant_id),
    -- The platform's one tenant id format (backlog #0-92), as on every table
    -- of this service with a tenant_id.
    CONSTRAINT chk_postmortem_paused_tenants_tenant_id_slug
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$'),
    -- What auth-service says the tenant may do; a tenant with full access is
    -- never here.
    CONSTRAINT chk_postmortem_paused_tenants_access
        CHECK (access IN ('READ_ONLY', 'NONE'))
);

COMMENT ON TABLE postmortem_paused_tenants
    IS 'Suspended tenants whose postmortems are not generated, and since when (backlog #0-82)';
