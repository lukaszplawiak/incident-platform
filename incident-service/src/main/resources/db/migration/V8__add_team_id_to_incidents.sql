-- Adds team_id to incidents — prerequisite for the Routing Engine epic.
--
-- Design: nullable UUID, no FK to auth-service.teams.
--
-- Why no FK across microservices:
--   incident-service and auth-service are separate deployable units with
--   separate databases. Cross-service foreign keys couple deployment order,
--   prevent independent schema evolution, and create distributed transaction
--   problems. Instead, referential integrity is enforced at the application
--   level: team_id values come from JWT claims (principal.teamIds()) which
--   are authoritative at the moment of the request.
--
-- Why nullable:
--   1. Existing incidents have no team assignment — backfilling is not feasible.
--   2. Incidents from ingestion-service may not have a team label initially.
--   3. "Unassigned" incidents (team_id IS NULL) are a valid business state.
--
-- Routing Engine note (backlog):
--   This column is a prerequisite for the Routing Engine feature:
--     Alert → Routing Rules → Service → Team → Escalation Policy → On-call User
--   When routing is implemented, team_id will be set automatically by
--   ingestion-service based on alert labels and routing rules.
--   Until then, team_id is set manually via PATCH /api/v1/incidents/{id}/team.

ALTER TABLE incidents
    ADD COLUMN team_id UUID;

CREATE INDEX idx_incidents_team_id
    ON incidents (team_id)
    WHERE team_id IS NOT NULL;

COMMENT ON COLUMN incidents.team_id
    IS 'Team responsible for this incident. NULL = unassigned. '
       'Set manually via PATCH /api/v1/incidents/{id}/team or automatically '
       'by ingestion routing rules (Routing Engine — backlog). '
       'No FK to auth-service.teams — cross-service referential integrity '
       'is enforced at application level via JWT teamIds claim.';