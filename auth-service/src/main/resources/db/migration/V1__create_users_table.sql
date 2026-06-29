-- Users table for auth-service.
--
-- Design decisions:
--
-- password_hash is NULLABLE — OAuth2 users (Google, GitHub) authenticate
-- via external provider and have no local password. When OAuth2 is added
-- (planned), a separate oauth_providers table will reference user_id.
-- This keeps the schema additive: email/password users and OAuth2 users
-- coexist without a breaking migration.
--
-- organization_id is NULLABLE — placeholder for future multi-org support.
-- Currently one tenant = one organisation. When org hierarchy is added
-- (e.g. a company with prod/staging/dev tenants), organization_id will
-- group multiple tenant_ids under one billing/management entity.
-- NULL means "not yet assigned to an org" — safe default.
--
-- Roles are in a separate user_roles table (many-to-many via user_id).
-- A single role column would require a migration to add granular
-- permissions later. The join table is additive: new permission rows
-- can be inserted without touching existing data.
--
-- tenant_id on both tables enables row-level multi-tenant isolation —
-- the same pattern used across all other platform services.

CREATE TABLE users
(
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    tenant_id       VARCHAR(255) NOT NULL,
    organization_id UUID,                          -- nullable: future org hierarchy
    email           VARCHAR(255) NOT NULL,
    password_hash   VARCHAR(255),                  -- nullable: OAuth2 users have no local password
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_users
        PRIMARY KEY (id),

    CONSTRAINT uq_users_email_tenant
        UNIQUE (email, tenant_id)
);

-- Roles table — one row per role assignment.
-- Initially: ROLE_ADMIN, ROLE_RESPONDER.
-- Future: ROLE_RESPONDER can be extended with granular permissions
-- (e.g. CAN_CLOSE_INCIDENT, CAN_MANAGE_ONCALL) by adding rows here
-- without changing the users table.
CREATE TABLE user_roles
(
    id        UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id   UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    tenant_id VARCHAR(255) NOT NULL,
    role      VARCHAR(100) NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_user_roles
        PRIMARY KEY (id),

    CONSTRAINT uq_user_role_per_tenant
        UNIQUE (user_id, tenant_id, role),

    CONSTRAINT chk_user_role
        CHECK (role IN ('ROLE_ADMIN', 'ROLE_RESPONDER'))
);

CREATE INDEX idx_users_tenant
    ON users (tenant_id);

CREATE INDEX idx_users_email_tenant
    ON users (email, tenant_id);

CREATE INDEX idx_users_organization
    ON users (organization_id)
    WHERE organization_id IS NOT NULL;

CREATE INDEX idx_user_roles_user
    ON user_roles (user_id);

CREATE INDEX idx_user_roles_tenant
    ON user_roles (tenant_id, role);

COMMENT ON TABLE users
    IS 'Platform user accounts. Supports email/password and OAuth2 (password_hash nullable).';
COMMENT ON COLUMN users.password_hash
    IS 'BCrypt hash. NULL for OAuth2-only accounts.';
COMMENT ON COLUMN users.organization_id
    IS 'Future: groups multiple tenants under one organisation. NULL until org hierarchy is implemented.';
COMMENT ON TABLE user_roles
    IS 'User role assignments per tenant. Designed for future granular permission extension.';