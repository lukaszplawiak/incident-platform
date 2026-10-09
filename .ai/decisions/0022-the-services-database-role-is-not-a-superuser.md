# ADR-0022: The services' database role is not a superuser (backlog #0-78)

- **Status:** Accepted
- **Backlog:** #0-78
- **Source:** migrated verbatim from `.ai/context/project.md`, section "Known Invariants and
  Limitations" (2026-10-05). The text below is the original record; it was not rewritten into the
  Context / Decision / Consequences form. New ADRs use `_template.md`.

## Record

Every service connects as `incident_app`: owner of `incidentdb`, of the `public` schema and of
every table, but not a superuser, with no role memberships. The image's `POSTGRES_USER` is the
admin, used by no service. Full model, procedures and limits: `docs/database-roles.md`. Not
inferable from the files:

- `k8s/base/infrastructure/postgresql-init.sh` is the one copy of the init script. docker-compose
  bind-mounts it from there because Kustomize cannot read files outside `k8s/base`. Do not move it
  to `docker/`. It must stay executable (100755), or the image sources it into its own shell.
- It runs only on an empty data directory. A database created before #0-78 has `incident_app` as
  its bootstrap role (OID 10), which PostgreSQL never lets lose SUPERUSER. That is why the
  migration (`docs/database-roles-migrate.sql`) renames it to the admin and creates a new
  `incident_app` instead of `ALTER ROLE ... NOSUPERUSER`.
- Readiness and the startup probe use TCP (`-h 127.0.0.1`), liveness the socket. The image's
  temporary init-phase server listens on the socket only, so a socket readiness check passed
  before the role existed (reproduced). A TCP liveness probe would fail during init and could
  restart the pod mid-init.
- No role name is hard-coded: probes, CI and the migration read `POSTGRES_USER` / `APP_DB_USER`.
- `spring.datasource.password` is `${DB_PASSWORD}` with no default (backlog #0-66). Spring Boot
  leaves an unresolved placeholder in place when binding `spring.datasource.*`, so a missing
  password reaches Postgres as the literal text and fails as "password authentication failed",
  not as a placeholder error. No in-app check on purpose: Kubernetes and compose stop earlier with
  a clear message. `.github/scripts/check-db-password-config.rb` (CI, with its own bypass test) requires every committed
  `spring.datasource.password` to be exactly `${DB_PASSWORD}`.
- The superuser never touches anything outside `pg_catalog` in `incidentdb`, not even to read it:
  the services' role owns the database and `public` and could have planted a trigger, rule, default, view or shadowing function there,
  which would run as whoever fires it. Service data, backups and restores go through an
  `incident_app` login. `SET ROLE incident_app` in an admin session is no boundary (planted code
  can `RESET ROLE`); the CI job demonstrates both cases. The admin's `search_path` is `pg_catalog`
  (init script and migration) as a second line of defence. The migration is the one exception:
  `search_path = pg_catalog`, schema-qualified names, only catalog queries and `ALTER ... OWNER`,
  and a preflight that refuses to run while an event trigger (fired by DDL) or an extra superuser
  exists. It is for databases not suspected of compromise; those are restored, not migrated.
- Extension scripts run with superuser rights even for trusted extensions; only image-shipped
  contrib extensions are created (by `incident_app`), untrusted ones into an admin-owned schema.
  `pg_isready` does not log in, so its `-U` only labels the check.
- Inside the container, the socket and `127.0.0.1` use `trust`. Any password test must go over a
  network, as `.github/scripts/test-postgres-roles.sh` does.
- A migration must not need superuser rights (only trusted extensions). The Testcontainers tests
  connect as a superuser and would not notice; the smoke test runs every migration as
  `incident_app`.
- What it does not solve (grants and RLS do not bind an owner; the connection budget) is in the
  guide's "What this protects" section and backlog #0-67 / #0-79.
