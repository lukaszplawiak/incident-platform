-- Adds team_id to oncall_schedules — prerequisite for team-based on-call routing.
--
-- Why team_id here:
--   Previously, on-call queries filtered only by tenant_id + role, returning
--   the first matching person regardless of team. With multiple teams in a tenant
--   (backend-team, security-team, ops-team), each with their own on-call rotation,
--   the query would return a random person from any team.
--
--   Adding team_id enables:
--     findCurrentOncallByTeamAndRole(tenantId, teamId, role, now)
--     → "who is PRIMARY for backend-team RIGHT NOW?"
--
-- Cross-service FK note:
--   No FK to auth-service.teams — oncall-service has its own database.
--   Referential integrity is enforced at application level: OncallScheduleService
--   validates that the team exists (via auth-service HTTP call) before creating
--   a schedule entry. This is the standard microservice pattern.
--
-- Nullable:
--   Existing schedules have no team assignment. The query falls back to
--   tenant-wide search when teamId is null (backward compatible).
--
-- Index:
--   Composite index on (tenant_id, team_id, role, starts_at, ends_at) supports
--   the hot path: "find current on-call for team X with role PRIMARY".

ALTER TABLE oncall_schedules
    ADD COLUMN team_id UUID;

CREATE INDEX idx_oncall_schedules_team_role_time
    ON oncall_schedules (tenant_id, team_id, role, starts_at, ends_at)
    WHERE team_id IS NOT NULL;

COMMENT ON COLUMN oncall_schedules.team_id
    IS 'Team this schedule entry belongs to. NULL = tenant-wide (legacy). '
       'No FK to auth-service.teams — cross-service referential integrity '
       'enforced at application level. '
       'Used by EscalationScheduler to find the correct on-call engineer '
       'for a given team: findCurrentOncallByTeamAndRole(tenantId, teamId, role).';