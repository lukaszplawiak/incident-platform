-- Tenants as records of their own (backlog #0-80).
--
-- Until now a tenant existed only as the tenant_id string on its rows: it
-- "existed" once it had a user. There was no way to onboard one at runtime.
-- The only paths were V1_1's seeded admin with the password 'changeme'
-- (removed by #0-80), the operator tenant's invite bootstrap, and /dev/token.
-- Tenants are now provisioned by a platform operator
-- (POST /api/v1/platform/tenants, TenantProvisioningService). This table makes
-- "this tenant exists" explicit, also when all of its users are archived. It
-- records who created the tenant and when, and whom its first admin invite went
-- to, so the operator can reissue that invite.
--
-- auth-service only: the other services keep treating tenant_id as a plain
-- string on their own rows. Suspending or offboarding a tenant is backlog #0-82;
-- the status column it needs is added there, not speculatively here.

CREATE TABLE tenants (
    -- Same type as users.tenant_id. New ids are validated as slugs by the
    -- service; backfilled ones are kept as they are.
    tenant_id         VARCHAR(255) PRIMARY KEY,
    display_name      VARCHAR(200) NOT NULL,
    -- The address the first admin invite went to; null for tenants that existed
    -- before this table (backfilled below).
    first_admin_email VARCHAR(255),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- The operator who provisioned it (users.id in the platform-operator tenant);
    -- null for backfilled tenants. No FK: the operator user is in another tenant
    -- and may be archived or anonymized later; the audit trail has the full record.
    created_by        UUID
);

-- The platform API lists tenants newest first (findAllByOrderByCreatedAtDescTenantIdAsc).
CREATE INDEX idx_tenants_created_at ON tenants (created_at DESC, tenant_id);

-- Every tenant that already has users, archived and anonymized ones included
-- (the tenant existed even if nobody in it is active any more). The id doubles
-- as the display name, cut to the column's 200 characters (an id may have 255).
INSERT INTO tenants (tenant_id, display_name, created_at)
SELECT tenant_id, left(tenant_id, 200), min(created_at)
FROM users
GROUP BY tenant_id
ON CONFLICT (tenant_id) DO NOTHING;
