# Review dimension: migration (Flyway)

Owned by the maintainer; agents never edit it. Moved from `.claude/agents/migration-reviewer.md` (and the
persistence check of `code-reviewer.md`) on 2026-10-05; the checks are unchanged, each now has an id.

Read by `review-migration` (the whole file, whenever it runs) and by the implementer: the rules the
architect's plan lists, or the whole file when the change reaches this area (self-check).

Read `.ai/context/architecture.md` ("Persistence") first: one shared PostgreSQL database (`incidentdb`),
each service owning its own tables and its own Flyway history table (`flyway_schema_history_<service>`),
and `ddl-auto: validate`, meaning a schema drift between entities and migrations fails the service at
startup rather than auto-correcting.

If there are no new or changed migration files and no entity changes in the diff, say so and return
`APPROVE`.

## Checks, in order of severity

- **MIG-01 Numbering correctness.** The new migration's `V<n>` must be strictly greater than the highest
  existing `V<n>` **in that service's own migration directory only** — numbering is per-service, not
  global. Flag a gap, or a duplicate within the same service; another service's sequence is irrelevant.
- **MIG-02 A migration already on `main` is never edited.** Flyway checksums it in every database where
  it ran. A change to an existing `V<n>` file that exists on the base branch is blocking (a hook also
  refuses it). A fix is a new migration.
- **MIG-03 `ddl-auto: validate` compatibility.** Does every entity change in this diff have a matching
  migration, and does every migration match what the entity now expects? A new `@Column` without a
  migration, or a migration that doesn't match the entity's expected type/nullability, is a
  startup-breaking bug — the highest-severity finding this review can raise.
- **MIG-04 Data safety on existing rows.** For any table that isn't brand new in this migration:
  - adding a `NOT NULL` column without a `DEFAULT` (or a backfill step) breaks on any existing row;
  - `DROP COLUMN` / `DROP TABLE`: is the data actually safe to lose, or does this need a soft-delete/archive
    step first? This codebase favors soft-delete and optimistic locking over destructive changes. A
    destructive change is `NEEDS_HUMAN`;
  - a type change (widening/narrowing a column, changing precision) that could silently truncate or
    reject existing data;
  - renaming a column/table: does application code (entity mappings, native queries) still reference the
    old name anywhere?
  From the first production release, every migration must stay compatible with the previous release
  (expand/contract), since pods of both run during a rollout (ADR-0015).
- **MIG-05 Index and constraint impact.** A new `UNIQUE` or `FOREIGN KEY` constraint added to a table
  that may already contain violating rows in any environment beyond a fresh local database. A large
  table getting a new index without `CONCURRENTLY`; a `CREATE INDEX CONCURRENTLY` sits alone in its file
  and the service sets `spring.flyway.postgresql.transactional-lock: false` (backlog #0-84).
- **MIG-06 No superuser rights.** Every service connects as `incident_app`, not a superuser: only
  *trusted* extensions, no `ALTER SYSTEM` / `COPY ... PROGRAM` / role changes, no hard-coded role name
  (backlog #0-78, ADR-0022). Testcontainers tests connect as a superuser and would not notice.
- **MIG-07 Tenant id.** A new table with a `tenant_id` carries the slug `CHECK` every other such table has
  (backlog #0-92).
- **MIG-08 Per-service isolation.** The migration only touches tables this service owns.
- **MIG-09 SQL correctness and style.** Snake_case naming matching the existing convention, no
  environment-specific hardcoded values, idempotent-safe only if that's the existing pattern in this
  service's migration directory (check a couple of prior migrations for the house style before flagging
  a deviation).

## Output notes

Order by how badly a finding could break a running system (startup-breaking `ddl-auto` mismatches and
data-loss risks first). For each: **What** (file and line), **What breaks** (startup crash, data loss on
existing rows, orphaned foreign keys), **Suggested fix** (a concrete migration change, following the
numbering and style of that service's migration directory).

## Calibration

Added only by `/apply-audit` from accepted audit recommendations. Empty until the first audit.
