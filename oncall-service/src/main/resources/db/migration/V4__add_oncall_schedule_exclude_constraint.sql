-- Adds a database-level guarantee against overlapping on-call schedules,
-- closing the check-then-act race window that OncallScheduleService.create
-- has between existsOverlappingForCreate(...) and save(...).
--
-- Two concurrent requests for the same tenant+team+role with overlapping
-- time windows could both pass the application-level check (neither has
-- committed yet) and both insert — this constraint makes that impossible
-- at the database level, regardless of application-layer timing.
--
-- btree_gist is a "trusted" extension (installable by any database-owning
-- role since PostgreSQL 13, no superuser needed) and ships as part of
-- PostgreSQL's own contrib collection, maintained by the same team that
-- maintains PostgreSQL itself — not a third-party dependency at risk of
-- abandonment. It's required here because EXCLUDE USING gist needs GiST-
-- compatible equality operators for tenant_id/team_id/role (plain text/uuid
-- columns), which GiST doesn't provide natively — btree_gist adds them.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- team_id is nullable (NULL = tenant-wide schedule, no team assigned — see
-- OncallSchedule's own domain comment). EXCLUDE constraints treat NULL as
-- NOT equal to NULL by default for the "=" operator (same as UNIQUE
-- constraints), meaning two NULL-team_id rows would never be considered
-- conflicting by this constraint — the opposite of the semantics the
-- application-level check enforces (NULL scoped as its own scope, not a
-- wildcard, but two NULLs among themselves DO conflict). COALESCE to a
-- fixed sentinel UUID makes NULL compare equal to itself for the purposes
-- of this constraint, matching existsOverlappingForCreate's own
-- "(s.teamId = :teamId OR (s.teamId IS NULL AND :teamId IS NULL))" logic.
ALTER TABLE oncall_schedules
    ADD CONSTRAINT excl_oncall_schedule_overlap
    EXCLUDE USING gist (
        tenant_id WITH =,
        COALESCE(team_id, '00000000-0000-0000-0000-000000000000'::uuid) WITH =,
        role WITH =,
        tstzrange(starts_at, ends_at) WITH &&
    );

COMMENT ON CONSTRAINT excl_oncall_schedule_overlap ON oncall_schedules IS
    'Atomic guarantee against overlapping on-call schedules for the same '
    'tenant+team+role, backing up the application-level check in '
    'OncallScheduleService.create (which handles the common case without '
    'a round trip to find out from a constraint violation, but cannot '
    'itself be atomic against concurrent requests).';