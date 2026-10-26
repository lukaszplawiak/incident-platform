# Review dimension: migration (Flyway)

Owned by the maintainer; agents never edit it. Moved from `.claude/agents/migration-reviewer.md` (and the
persistence check of `code-reviewer.md`) on 2026-10-05, each check with an id; rewritten as invariants on
2026-10-26 (backlog #0-115): each rule says what is true of a migration, and its "Check:" line what to look for in a
diff. No id changed its meaning.

Read by `review-migration` (the whole file, whenever it runs) and by the implementer: the rules the
architect's plan lists, or the whole file when the change reaches this area (self-check). For the implementer a rule
is what the migration must be; for the reviewer, its "Check:" line is where a violation shows. `_common.md` defines
the two labels ("Enforced by", "Backed by").

Read `.ai/context/architecture.md` ("Persistence") first: one shared PostgreSQL database (`incidentdb`),
each service owning its own tables and its own Flyway history table (`flyway_schema_history_<service>`),
and `ddl-auto: validate`, meaning a schema drift between entities and migrations fails the service at
startup rather than auto-correcting.

If there are no new or changed migration files and no entity changes in the diff, say so and return
`APPROVE`.

## Rules, in order of severity

- **MIG-01 A migration's number follows its own service's sequence.** The new `V<n>` is the next number above the
  highest `V<n>` **in that service's own migration directory**: numbering is per service, not global, so another
  service's sequence is irrelevant. Check: the new file's `V<n>` against `ls` of that directory, for a gap or a
  duplicate.
- **MIG-02 A migration already on `main` is never edited.** Flyway checksums it in every database where it ran, so
  an edited one fails validation there; a fix is a new migration. Backed by: `check-factory-guards.sh` (a migration
  that exists in the base and was modified or deleted) and `guard-migrations.sh` (the edit, in a session). Check: a
  change to a migration that is on the base. Factory guards pass it when the owner wrote the PR or approved its
  commit, and the hook fails open without both `jq` and `python3`, or without a base ref.
- **MIG-03 Entities and migrations agree.** Every entity change has its migration and every migration matches what
  the entity expects: type, nullability, length, name. A mismatch the validation sees fails the service at startup,
  the highest-severity finding this review can raise. Backed by: the Docker Compose smoke test, which starts every
  service with `ddl-auto: validate` on the migrated schema (tables, columns and types); nullability and length are
  left to this review. Check: each `@Column`, `@Table`, `@Version` or relation the diff adds or changes, against the
  migration's DDL, including `NOT NULL` and length.
- **MIG-04 Existing rows survive the migration.** On a table that is not new in this migration:
  - a `NOT NULL` column comes with a `DEFAULT` or a backfill step before the constraint;
  - a `DROP COLUMN` or `DROP TABLE` loses only data that is safe to lose; this codebase favors soft-delete and
    optimistic locking over destructive changes, so a destructive change is `NEEDS_HUMAN`;
  - a type change does not truncate or reject existing values (narrowing, lower precision);
  - a rename leaves no reference to the old name (entity mappings, native queries).
  From the first production release, every migration stays compatible with the previous release (expand/contract),
  since pods of both run during a rollout (ADR-0015). Check: every `ALTER TABLE` and `DROP` on an existing table,
  and what it does to a row that is already there.
- **MIG-05 A constraint or an index on an existing table does not lock it or fail on its rows.** A new `UNIQUE` or
  `FOREIGN KEY` on a table that may already hold violating rows, anywhere beyond a fresh local database, is added
  `NOT VALID` and validated in a later migration, or comes with a check that no row violates it. A large table gets
  a new index `CONCURRENTLY`; a `CREATE INDEX CONCURRENTLY` sits alone in its file, and the service sets
  `spring.flyway.postgresql.transactional-lock: false` (incident-service, backlog #0-84). Check: a new constraint or
  index on a table that is not new in this migration, and how it is added.
- **MIG-06 A migration needs no superuser rights.** Every service connects as `incident_app`, not a superuser: only
  *trusted* extensions, no `ALTER SYSTEM`, no `COPY … PROGRAM`, no role changes, no hard-coded role name (backlog
  #0-78, ADR-0022). Backed by: the Docker Compose smoke test, where the services migrate as `incident_app`;
  Testcontainers tests connect as a superuser and would not notice. Check: every extension, `ALTER SYSTEM`, `COPY`,
  `GRANT`, `ALTER ROLE` or role name in the migration.
- **MIG-07 A table with a `tenant_id` checks the slug.** It carries the same slug `CHECK` every other such table has
  (backlog #0-92). Check: a new table, or a new `tenant_id` column, and its `CHECK`.
- **MIG-08 A migration touches only its own service's tables.** Check: every table the migration names, against
  the tables that service's earlier migrations created.
- **MIG-09 A migration follows its directory's style.** Snake_case names matching the existing convention, no
  environment-specific values, and idempotent statements (`IF NOT EXISTS`) only where that service's migrations
  already use them. Check: two prior migrations of the same directory, before flagging a deviation.

## Output notes

Order by how badly a finding could break a running system (startup-breaking `ddl-auto` mismatches and
data-loss risks first). For each: **What** (file and line), **What breaks** (startup crash, data loss on
existing rows, orphaned foreign keys), **Suggested fix** (a concrete migration change, following the
numbering and style of that service's migration directory).

## Calibration

Added only by `/apply-audit` from accepted audit recommendations. Empty until the first audit.
