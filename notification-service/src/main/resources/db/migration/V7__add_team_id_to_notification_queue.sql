-- Carries the incident's team through the notification outbox (backlog #0-12).
--
-- Problem: none of the IncidentXxxEvent records on incidents.lifecycle carried
-- a team id, so NotificationRouter's PRIMARY on-call lookup was always
-- tenant-wide, never scoped to the incident's team — in a multi-team tenant it
-- could notify another team's PRIMARY. Investigating this also surfaced a
-- second, more severe bug on the escalation-service side: IncidentOpenedEvent
-- never carried teamId either, so EscalationTask.teamId was always NULL in
-- production, and the automatic SECONDARY/MANAGER escalation chain never
-- resolved a real target. Both are fixed together in this PR, since the root
-- cause (teamId must flow through incident events) is shared.
--
-- team_id here is IncidentXxxEvent.teamId, read off every event type (not
-- just INCIDENT_ESCALATED) by IncidentEventConsumer and stored so
-- NotificationScheduler can pass it to oncall-service's already team-aware
-- /api/v1/oncall/current?teamId=...&role=... at send time (recipient is
-- resolved at process time, not enqueue time — see NotificationService's
-- Javadoc). NULL for incidents with no team assignment (manually created, or
-- an Integration without a team), in which case the lookup stays tenant-wide,
-- unchanged from before this column existed.
--
-- Deliberately NOT part of the idempotency key
-- (uq_notification_queue_incident_tenant_event_level, added by V5): it is
-- routing/informational data, not part of what makes a notification unique
-- for a given incident/tenant/event/level — the same treatment V5 already
-- gave escalate_to.
--
-- Rollout window: nullable column, no default needed beyond NULL. Old pods
-- (pre-migration code, running IncidentEventConsumer/NotificationService
-- without the teamId parameter) simply don't write it — inserts still
-- succeed, routing for their rows stays tenant-wide, same as today. New pods
-- write the real value. No backfill of existing rows is possible or needed:
-- their team, if any, was never captured on the wire.
--
-- Locking: see V5's comment for the full account of ADD COLUMN's ACCESS
-- EXCLUSIVE lock and why SET LOCAL lock_timeout only bounds the wait, not the
-- run — same reasoning applies here, on the same (small, outbox) table.

SET LOCAL lock_timeout = '5s';

ALTER TABLE notification_queue
    ADD COLUMN team_id UUID;

COMMENT ON COLUMN notification_queue.team_id
    IS 'Team the incident belongs to (IncidentXxxEvent.teamId), or NULL. Used to scope the '
       'oncall-service PRIMARY lookup to the team at send time; NULL falls back to tenant-wide. '
       'Not part of the idempotency key.';
