-- Introduces Teams — groups of users within a tenant.
--
-- Model: tenant → teams (many) → users (many-to-many via team_members)
--
-- Design decisions:
--
-- teams.deleted_at (soft delete):
--   Mirrors users.deleted_at. @SQLRestriction("deleted_at IS NULL") on the
--   Team entity filters soft-deleted teams from all Hibernate queries.
--   A 30-day restore window is planned (backlog) — hard deletes would make
--   recovery impossible. When a team is soft-deleted, its team_members rows
--   are NOT deleted — they are retained for the restore window.
--
-- team_members.team_role:
--   Two levels of role exist in the system:
--   1. tenant-level: user_roles.role (ROLE_ADMIN/ROLE_RESPONDER) — who can
--      manage the platform.
--   2. team-level: team_members.team_role (MANAGER/RESPONDER) — who manages
--      a specific team's schedules and members.
--   This mirrors the PagerDuty model: account-level role vs. team-level role.
--
-- teamIds in JWT:
--   On login, AuthService loads the user's team UUIDs and includes them in
--   the JWT claim "teamIds". This allows downstream services (incident,
--   notification, escalation) to check team membership without calling
--   auth-service — stateless authorization at the expense of a slightly
--   larger token (~36 bytes per team UUID). Acceptable for B2B systems
--   where users belong to 2-5 teams.
--
-- users.organization_id removed:
--   Was a nullable placeholder with no FK and no usage. Replaced by the
--   team_members many-to-many relationship which correctly models the
--   "user belongs to multiple teams" requirement.

-- ── teams ─────────────────────────────────────────────────────────────────

CREATE TABLE teams
(
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    tenant_id   VARCHAR(255) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at  TIMESTAMPTZ,

    CONSTRAINT pk_teams
        PRIMARY KEY (id),

    CONSTRAINT uq_teams_name_tenant
        UNIQUE (name, tenant_id)
            DEFERRABLE INITIALLY DEFERRED  -- allows rename + recreate in same tx
);

CREATE INDEX idx_teams_tenant
    ON teams (tenant_id)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE teams
    IS 'Groups of users within a tenant. Mirrors PagerDuty Teams model.';
COMMENT ON COLUMN teams.deleted_at
    IS 'Soft delete. NULL = active. 30-day restore window planned (backlog).';

-- ── team_members ──────────────────────────────────────────────────────────

CREATE TABLE team_members
(
    team_id   UUID         NOT NULL REFERENCES teams (id) ON DELETE CASCADE,
    user_id   UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    team_role VARCHAR(30)  NOT NULL,
    joined_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_team_members
        PRIMARY KEY (team_id, user_id),

    CONSTRAINT chk_team_member_role
        CHECK (team_role IN ('MANAGER', 'RESPONDER'))
);

CREATE INDEX idx_team_members_user
    ON team_members (user_id);

CREATE INDEX idx_team_members_team
    ON team_members (team_id);

COMMENT ON TABLE team_members
    IS 'Many-to-many between users and teams with per-team role.';
COMMENT ON COLUMN team_members.team_role
    IS 'MANAGER: can manage team members and schedules. '
       'RESPONDER: receives and responds to incidents.';

-- ── users: drop organization_id ───────────────────────────────────────────

-- organization_id was a nullable placeholder with no FK and no usage.
-- Team membership is now modelled via team_members (many-to-many).
ALTER TABLE users
DROP COLUMN IF EXISTS organization_id;