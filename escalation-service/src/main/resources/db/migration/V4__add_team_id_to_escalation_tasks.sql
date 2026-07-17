-- Adds team_id to escalation_tasks.
--
-- Why team_id here:
--   EscalationScheduler needs to know which team owns each incident
--   to call oncall-service: GET /api/v1/oncall/current?teamId=xxx&role=PRIMARY
--
--   The teamId is captured from the IncidentOpenedEvent (via Incident.team_id)
--   when the EscalationTask is created by IncidentEventConsumer.handleOpened().
--   It is then read by EscalationScheduler.escalate() for the HTTP call.
--
-- Nullable:
--   Incidents created before Integration-based routing (or created manually
--   via API without a team assignment) have team_id = NULL on both the incident
--   and the escalation task. EscalationScheduler logs a warning and skips
--   on-call routing when team_id is null (fail-loudly — no silent fallback).

ALTER TABLE escalation_tasks
    ADD COLUMN team_id UUID;

COMMENT ON COLUMN escalation_tasks.team_id
    IS 'Team responsible for this incident — copied from Incident.team_id '
       'at task creation time. NULL = no team assigned, on-call routing skipped. '
       'Used by EscalationScheduler to call oncall-service HTTP endpoint: '
       'GET /api/v1/oncall/current?teamId={team_id}&role=PRIMARY';