-- Soft-delete for on-call schedules (backlog #44).
--
-- Fixed: OncallScheduleService previously physically DELETEd a schedule
-- row on removal — inconsistent with the rest of this platform's
-- established convention (EscalationTaskStatus.CANCELLED,
-- IncidentStatus's full lifecycle with no delete endpoint at all) that
-- time-bound domain entities are never physically removed, only moved to
-- a terminal status. Also a genuine, reachable bug introduced by V5: a
-- SUPERSEDED row is referenced by its replacement's supersedes_id
-- foreign key, so physically deleting a superseded row would be
-- rejected by that constraint with an unhandled
-- DataIntegrityViolationException (500) — a schedule can never actually
-- be deleted once it has been superseded at least once.
--
-- CANCELLED added to the existing status enum rather than introducing a
-- separate boolean/timestamp column, for the same reason SUPERSEDED
-- already lives here: one status column, one place every "is this row
-- still in effect" query already checks (status = ACTIVE).
ALTER TABLE oncall_schedules
DROP CONSTRAINT chk_oncall_schedule_status;

ALTER TABLE oncall_schedules
    ADD CONSTRAINT chk_oncall_schedule_status
        CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'CANCELLED'));

COMMENT ON COLUMN oncall_schedules.status
    IS 'ACTIVE = currently valid; SUPERSEDED = replaced via an edit; '
       'CANCELLED = removed via DELETE /schedules/{id} (soft — the row '
       'is kept for history). All three non-ACTIVE-excluded states are '
       'excluded from "current on-call" queries and from the '
       'overlap-detection constraint.';

-- No change needed to excl_oncall_schedule_overlap (V5) — it is already
-- scoped to WHERE (status = 'ACTIVE'), so a CANCELLED row is
-- automatically excluded from the conflict check too, with no further
-- migration required.