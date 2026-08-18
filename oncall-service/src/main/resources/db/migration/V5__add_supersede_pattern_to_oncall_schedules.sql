-- Supersede pattern for editing on-call schedules — see this table's own
-- prior lack of any UPDATE endpoint. OncallScheduleService previously only
-- supported create (INSERT) and delete (DELETE) — "editing" a schedule
-- meant two separate API calls (DELETE then POST), leaving a real window
-- between them where escalation-service/notification-service querying
-- "who is on-call right now" would find no one at all. Modeled after how
-- PagerDuty handles this: an edit never removes the original row — it
-- creates a new one that takes precedence, keeping the original around
-- (here: re-labeled, not physically deleted) for history and to guarantee
-- there is never a moment where the row set has zero coverage for that
-- window.
ALTER TABLE oncall_schedules
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT chk_oncall_schedule_status
            CHECK (status IN ('ACTIVE', 'SUPERSEDED')),
    ADD COLUMN supersedes_id UUID
        REFERENCES oncall_schedules(id),
    ADD COLUMN superseded_at TIMESTAMPTZ;

COMMENT ON COLUMN oncall_schedules.status
    IS 'ACTIVE = currently valid; SUPERSEDED = replaced via an edit, kept '
       'for history, excluded from "current on-call" queries and from the '
       'overlap-detection constraint below.';

COMMENT ON COLUMN oncall_schedules.supersedes_id
    IS 'Set on the NEW row when it replaces an existing one via '
       'POST /schedules/{id}/supersede — points backward to the row it '
       'replaces. NULL for rows created directly or never edited. '
       'Deliberately one-directional (new -> old) rather than also '
       'storing a forward pointer on the old row, to avoid two columns '
       'that would need to always stay in sync — "what replaced this '
       'row" is a simple WHERE supersedes_id = :id query.';

COMMENT ON COLUMN oncall_schedules.superseded_at
    IS 'When this row was superseded — NULL while ACTIVE.';

-- Fixed: the existing EXCLUDE constraint (V4) would otherwise reject a
-- supersede's INSERT — the old row is still physically present (same
-- tenant/team/role, overlapping time range) at the moment the new row is
-- inserted, and an unscoped EXCLUDE constraint can't tell "this is the
-- row being replaced" from "this is a genuine conflict". Scoping the
-- constraint to ACTIVE rows only (PostgreSQL's EXCLUDE, like a unique
-- index, supports a partial WHERE clause) means the old row stops
-- participating in the overlap check the moment its own UPDATE (marking
-- it SUPERSEDED) is applied — both happen in the same transaction as the
-- new row's INSERT, so the constraint sees the correct, final state by
-- the time it's actually checked (deferred to statement end, same as
-- before).
ALTER TABLE oncall_schedules
DROP CONSTRAINT excl_oncall_schedule_overlap;

ALTER TABLE oncall_schedules
    ADD CONSTRAINT excl_oncall_schedule_overlap
    EXCLUDE USING gist (
        tenant_id WITH =,
        COALESCE(team_id, '00000000-0000-0000-0000-000000000000'::uuid) WITH =,
        role WITH =,
        tstzrange(starts_at, ends_at) WITH &&
    )
    WHERE (status = 'ACTIVE');

COMMENT ON CONSTRAINT excl_oncall_schedule_overlap ON oncall_schedules IS
    'Atomic guarantee against overlapping ACTIVE on-call schedules for the '
    'same tenant+team+role. Scoped to status = ACTIVE (backlog #43''s '
    'supersede pattern) so a superseded row — kept for history, not '
    'deleted — never counts as a conflict against its own replacement or '
    'anything else.';

-- Supports "what did this row get replaced by" lookups and keeps the
-- foreign key indexed (Postgres does not index FK columns automatically).
CREATE INDEX idx_oncall_schedules_supersedes_id
    ON oncall_schedules (supersedes_id)
    WHERE supersedes_id IS NOT NULL;