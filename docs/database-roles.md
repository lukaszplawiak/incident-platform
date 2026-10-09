# Database roles

How the platform's PostgreSQL roles are set up, what that does and does not protect, how to verify
it, and how to move an existing database to it without losing its data. Background: backlog #0-78
(fixed) and #0-67 (open).

## The model

| Role | Used by | Rights |
|---|---|---|
| admin (`POSTGRES_USER`, `postgres` by default) | a person or a tool, for administration (roles, passwords, catalog checks; see [Working as the admin](#working-as-the-admin)) | superuser |
| `incident_app` (`DB_USER`) | every service, at runtime and for Flyway migrations | owner of the `incidentdb` database, of the `public` schema and of every table in it; **not** a superuser, cannot create roles or databases, no replication, no `BYPASSRLS`, member of no other role |

No service connects as the admin. Nothing in the repository assumes the admin's name: the probes,
the CI checks and the migration read it from `POSTGRES_USER`.

### What this protects, and what it does not (yet)

Until backlog #0-78, the official image created `incident_app` itself through `POSTGRES_USER`,
which makes the role a superuser. So a SQL injection in any of the seven services could:
- run commands in the database container (`COPY ... TO PROGRAM`);
- read and write the server's files;
- create roles;
- grant itself anything.

As a plain owner, `incident_app` can do what the services need (create and alter its tables, create
the *trusted* extensions the migrations use, such as `btree_gist`). It can no longer do any of the
four things above **directly**.

What it still can, until backlog #0-67:

- **Every table of every service.** It owns them all, and an owner is not bound by grants. A SQL
  injection in postmortem-service can still read auth-service's password hashes.
- **Bypass Row-Level Security.** An owner bypasses RLS on its tables unless the table has
  `FORCE ROW LEVEL SECURITY`. `BYPASSRLS` being off does not change that. The platform uses no RLS
  today, and tenant isolation is enforced by the services' queries.
- **Change its own objects.** It can drop or alter its tables, change their grants, drop the
  database, and set database-level parameters.
- **Plant code for a superuser to run.** It can create triggers, rules, column defaults and
  constraints on its tables, replace a table with a view, and create functions and operators in
  `public`. Such code runs with the rights of whoever runs the statement that fires it. A function
  or operator in `public` can also shadow a built-in for any session that searches `public` (the
  pattern of CVE-2018-1058). So **any** statement a superuser runs on an object in `public`, a read
  included, can run planted code as a superuser, and everything above is back. `SET ROLE` does not
  prevent this: the planted code can `RESET ROLE`. The rules that keep the admin out of reach are in
  [Working as the admin](#working-as-the-admin).

#0-67 closes this. Each service gets its own role, owning only its own tables, and migrations and
runtime are split: an owner role for Flyway, a DML-only role for requests, which is bound by grants
and by RLS and cannot create objects. That only works on top of this setup, because nothing binds a
superuser.

**Connections.** The services' role cannot use the connections PostgreSQL reserves for superusers
(`superuser_reserved_connections`, 3 by default), so it gets 97 of the default `max_connections`
100. The reserve lets the admin log in even when the services have taken every other connection.

Every service that uses the database keeps a Hikari pool:
- incident-service: 10 by default;
- the other five: 5 each (auth-service hard-codes it, the others default to it);
- in Kubernetes, `app-config` sets `DB_POOL_SIZE: 5`, which incident-service reads too.

That makes 35 connections with one pod per service locally, and 30 in Kubernetes. The counts at the
HPAs' maximum replicas, and during a rolling update in which every Deployment adds its one surge
pod at the same time (the worst case):

| Overlay directory (namespace it deploys to) | HPA maximum | Rolling update |
|---|---|---|
| `k8s/overlays/prod` (`incident-platform-staging`), base HPAs | 70 | 100 |
| `k8s/overlays/staging` (`incident-platform-prod`), incident-service up to 5 | 80 | 110 |
| `k8s/overlays/dev` (`incident-platform-dev`), every HPA at 1 | 30 | 60 |

The staging and prod directories deploy to each other's namespaces (backlog #0-29). Either way,
both can exceed the 97 that the services' role gets. Sizing this is backlog #0-79.

### Working as the admin

The admin is a superuser in a database that the services' role owns, together with every object
in it, and that could have been changed through a SQL injection. As the database owner the role
can also create schemas besides `public`. The rule is simple: **the superuser never touches
anything outside `pg_catalog` in this database**, not even to read it. Everything that does is done
by logging in as `incident_app`, whose rights planted code cannot exceed.

| Task | Log in as | How |
|---|---|---|
| Read or change service data, run ad-hoc SQL on service tables | `incident_app` | `docker exec -it incident-postgres psql -U incident_app -d incidentdb` (Kubernetes: `kubectl exec -it -n <ns> postgresql-0 -- psql -U incident_app -d incidentdb`). Inside the container the socket uses `trust`, so no password is needed. |
| Back up and restore | `incident_app` | See [Backup and restore](#backup-and-restore). A superuser `pg_restore` would fire restored triggers and constraints as a superuser. |
| Create a *trusted* extension (all the platform uses: `uuid-ossp`, `pg_trgm`, `btree_gist`) | `incident_app` | `CREATE EXTENSION <name>;`, as a Flyway migration does (oncall-service V4). Only contrib extensions shipped with the image. |
| Create an *untrusted* extension | admin | Into a schema the admin owns, never `public`: `CREATE SCHEMA ext AUTHORIZATION postgres; CREATE EXTENSION <name> SCHEMA ext;`. The services then need `ext` qualified or on their `search_path`. None is needed today. |
| Roles, passwords, the catalog checks in [Verify](#verify) | admin | `docker exec -it incident-postgres psql -U postgres -d incidentdb` |

Every extension script, a trusted one included, runs with superuser rights. A trusted extension
only lets a non-superuser start it, and the objects it creates belong to the caller. The script
searches its target schema, so in a schema the services' role can write to, a name in the script
could resolve to something planted there (the class of CVE-2018-1058 and CVE-2022-2625). Two
things keep that out of reach:
- PostgreSQL's own contrib extensions are written for exactly this case and have been hardened in
  those fixes, so the platform creates nothing else;
- the image is kept current (backlog #0-71), so those fixes are in.

An untrusted extension goes into a schema only the admin can write to. The init script itself
creates `uuid-ossp` and `pg_trgm` as the admin in `public`. That is safe: on a fresh volume
`public` is still empty, and no service has connected yet.

#### Backup and restore

Both as `incident_app`, which owns every service object:

```bash
# Back up (Kubernetes: kubectl exec -n <ns> postgresql-0 -- pg_dump ... > incidentdb.dump)
docker exec incident-postgres pg_dump -U incident_app -d incidentdb -Fc > incidentdb.dump

# Restore into a database the init script has just created (an empty volume)
docker cp incidentdb.dump incident-postgres:/tmp/incidentdb.dump
docker exec incident-postgres sh -c '
  pg_restore -l /tmp/incidentdb.dump | grep -v " COMMENT - EXTENSION " > /tmp/toc.list &&
  pg_restore -U incident_app -d incidentdb --exit-on-error --no-owner -L /tmp/toc.list /tmp/incidentdb.dump'
```

The extensions the init script created belong to the admin. So their comments are left out of the
restore: only an extension's owner may set its comment, and `--exit-on-error` would stop on that.
CI runs exactly these commands ("PostgreSQL Roles", step 4). On 2026-10-01 they also restored the
local platform database (28 tables) with exit code 0.

- **Roles and passwords are not in the dump.** On the target they come from the init script and
  its environment, which is why the restore goes into a volume the script has just set up.
- **Planted code fails safely.** If the dump contains a trigger planted through a SQL injection,
  restoring the data fires it as `incident_app`, so it can do no more than the services could. With
  `--exit-on-error` the restore stops at the first such failure, which flags the dump for a look.
- **Kubernetes:** `kubectl cp incidentdb.dump <ns>/postgresql-0:/tmp/incidentdb.dump`, then the
  same `sh -c '...'` through `kubectl exec -n <ns> postgresql-0 --`. These two commands have not
  been run against a cluster.

**Why not `SET ROLE incident_app` in an admin session?** It does not hold. A planted trigger can
run `RESET ROLE` and continue as the superuser. CI shows it: the "PostgreSQL Roles" job plants such
a trigger, fires it once from an admin session under `SET ROLE`, where it creates a superuser role,
and once from an `incident_app` login, where it fails with "permission denied to create role".

**The admin's `search_path` is `pg_catalog`.** The init script and the migration set it with
`ALTER ROLE ... SET`. It is a second line of defence, not the rule above:
- it keeps an unqualified name in an admin session from resolving to something planted in
  `public`, for example a function shadowing a built-in;
- it applies when a session starts, and a session can still override it (`SET search_path`, or
  `PGOPTIONS`);
- it does nothing against triggers, rules, defaults, constraints or views, which fire whatever the
  `search_path`. That is why the admin stays out of `public` altogether.

**The migration is the one exception.** It runs as a temporary superuser on objects in `public`,
because it has to change their owner. It stays safe:
- it sets `search_path = pg_catalog` for its own session;
- it names every object with its schema;
- it runs only catalog queries and `ALTER ... OWNER`, with no DML, so no trigger, rule, default or
  constraint fires;
- before any DDL it refuses to run if an event trigger exists. Event triggers are the one kind of
  planted code that DDL does fire, as whoever runs it. It also refuses if a superuser other than
  the bootstrap role exists.

## How it is created

`k8s/base/infrastructure/postgresql-init.sh` runs once, from `/docker-entrypoint-initdb.d`, when the
data directory is **empty**.
- docker-compose bind-mounts it.
- Kubernetes mounts it from the `postgresql-init` ConfigMap, which `k8s/base/kustomization.yml`
  generates.
- Kustomize cannot read files outside `k8s/base`, so the one copy of the script lives there.
- The script must stay executable (git mode 100755). If it is not, the image *sources* it into its
  own shell instead of running it.

The image's `POSTGRES_USER` / `POSTGRES_PASSWORD` define the admin. The script reads two more
variables:

| Variable | docker-compose source | Kubernetes source |
|---|---|---|
| `POSTGRES_USER` (admin) | `postgres` | Secret `postgresql-admin`, key `username` (per overlay) |
| `POSTGRES_PASSWORD` (admin) | `POSTGRES_ADMIN_PASSWORD` in `docker/.env` (required) | Secret `postgresql-admin`, key `password` (per overlay) |
| `APP_DB_USER` | `incident_app` | ConfigMap `app-config`, key `DB_USER` |
| `APP_DB_PASSWORD` | `DB_PASSWORD` in `docker/.env` (required) | Secret `app-secrets`, key `DB_PASSWORD` (per overlay) |

The services read their password from the same source (`DB_PASSWORD`), so the role and the services
cannot disagree on it. A service started with `./mvnw spring-boot:run` does not see `docker/.env`. It
needs `spring.datasource.password` in its `application-local.yml` (README "Running Locally", Step 2),
set to the same value. `application.yml` has no default (backlog #0-66), so no service falls back to
a password of its own. The dev value `incident_secret` exists only where a developer picks it on
purpose: `docker/.env.example`, the dev overlay and the README's `application-local.yml` template.
ingestion-service does not use the database. The script reads the values
inside psql (`\getenv`), so no password appears on a command line. It refuses to run if
`APP_DB_USER` equals `POSTGRES_USER`.

CI tests all of this on every run (job "PostgreSQL Roles", `.github/scripts/test-postgres-roles.sh`):
- the script on an empty volume, with a non-default admin name and a password containing quotes;
- the guard against `APP_DB_USER` equal to `POSTGRES_USER`;
- the migration below.

The docker-compose smoke test then checks the role after every service has run its migrations as it.

## Fresh setup

### docker-compose

1. Create `docker/.env` from the template. `DB_PASSWORD` and `POSTGRES_ADMIN_PASSWORD` are
   required, and `docker compose` refuses to start without them. The template's values are for
   development only. `GRAFANA_ADMIN_PASSWORD` is required too and empty in the template on purpose
   (Grafana reads every tenant's logs, backlog #0-94 step 2): set your own, or every `docker compose`
   command below stops with its message.

   ```bash
   cp docker/.env.example docker/.env
   ```

2. Start from an empty volume:

   ```bash
   make dev-reset    # only if a postgres_data volume already exists, and only if its data can go
   docker compose -f docker/docker-compose.yml up -d --wait postgres
   docker compose -f docker/docker-compose.yml logs postgres | grep "Created application role"
   ```

   Expected: `Created application role 'incident_app' (not a superuser) owning database 'incidentdb'.`

3. Start the services. The first start of each service runs its Flyway migrations as `incident_app`.

Services started with `./mvnw spring-boot:run` (README "Running Locally", Option A) do not read `docker/.env`. Give
each of them `spring.datasource.password` in its `application-local.yml` (README Step 2), or export `DB_PASSWORD` in
the shell that starts it.

### Kubernetes

1. In the overlay's `secrets.yml`, set base64 values for `app-secrets` → `DB_PASSWORD` and for the
   `postgresql-admin` Secret (`username`, `password`).
   - The dev overlay ships dev values.
   - Staging and prod ship `REPLACE_WITH_...` placeholders that must be replaced before deploying.
   - Real values belong in an external secret store, not in git.
2. Deploy as usual (`kubectl apply -k k8s/overlays/<env>`). The PersistentVolumeClaim is empty on the
   first deploy, so the script runs.
   - The `startupProbe` waits up to 10 minutes for initdb and the script, and holds the liveness
     probe back until then.
   - Readiness goes over TCP, because the image's temporary init-phase server listens on its socket
     only.

### A missing or wrong password

`application.yml` reads `${DB_PASSWORD}` with no default (backlog #0-66), so a service never falls back
to a dev password. Where the password is missing, this is what you see:

| How it runs | Missing password stops it at | Message |
|---|---|---|
| Kubernetes | the pod, before the service starts | `CreateContainerConfigError`, `couldn't find key DB_PASSWORD in Secret ...` |
| docker-compose | `docker compose`, before anything starts | `required variable DB_PASSWORD is missing a value: set DB_PASSWORD in docker/.env ...` |
| `./mvnw spring-boot:run` | the service's first connection | `password authentication failed for user "incident_app"` |

The last message reads like a wrong password, not a missing one. An *empty* `DB_PASSWORD` (a
Kubernetes Secret whose key exists but is empty, or `DB_PASSWORD=` in a shell) gets past
`secretKeyRef` and reaches Postgres too, with the same message. Compose rejects it, because
`${VAR:?}` treats empty as missing. Spring Boot leaves an unresolved
`${DB_PASSWORD}` in place for `spring.datasource.*` instead of failing on it, so the service sends
that text as the password. Check `spring.datasource.password` in `application-local.yml` first. The
same message with the password set means it differs from the role's (see
[Changing a password later](#changing-a-password-later)). A CI step ("No service has a default
database password", `.github/scripts/check-db-password-config.rb`) reads every committed Spring
config of every module and fails when any of these hold:
- `spring.datasource.password` is anything but `${DB_PASSWORD}`;
- another datasource or Flyway password is a literal or has a default;
- the JDBC URL carries a password;
- a service has a datasource but no password.

Its own bypass test (`test-db-password-config.sh`) runs first.

### If the first start fails

If the init script fails (a missing variable, a typo), the image stops, but the data directory
already exists. The next start will **not** run the script again, and you get a database without
`incident_app`. Fix the cause, remove the volume (`make dev-reset`, or delete the PVC in Kubernetes)
and start again. Don't keep the logs of a failed start around: if `CREATE ROLE` itself failed, the
server log contains that statement, password included.

### A migration that runs outside a transaction (incident-service V13)

`CREATE INDEX CONCURRENTLY` cannot run in a transaction, so Flyway runs such a migration without one
(backlog #0-84). If it fails or is interrupted (the pod is killed during the build), Postgres keeps an
INVALID index and Flyway a failed history row, and incident-service refuses to start
(`validate-on-migrate`) until the row is repaired. As `incident_app`: run `flyway repair` with the
service's history table (`flyway_schema_history_incident`), or delete that failed row by hand, then
start the service again. The migration drops a leftover INVALID index itself before it builds again.
Flyway's lock is session-level in incident-service (`spring.flyway.postgresql.transactional-lock: false`,
which the concurrent build needs): connect it to Postgres directly, never through a proxy that pools by
transaction (PgBouncer in transaction mode).

## Verify

The commands use the default names. Inside the container, the socket and `127.0.0.1` use `trust`
authentication (the image's default), so these checks need no password, and they also don't test
one. Passwords are tested over the network by the CI job above.

```bash
# docker-compose (Kubernetes: kubectl exec -n <namespace> postgresql-0 -- psql ...)
docker exec incident-postgres psql -U postgres -d incidentdb -tAc \
  "SELECT rolname, rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls FROM pg_roles WHERE rolname IN ('postgres', 'incident_app') ORDER BY rolname"
```

Expected:

```
incident_app|f|f|f|f|f
postgres|t|t|t|t|t
```

`incident_app` must not be a member of any role. A grant of, for example,
`pg_execute_server_program` would bring `COPY ... TO PROGRAM` back without making it a superuser.
`COPY ... TO PROGRAM` itself must be refused, and the database should have no schema besides `public`
(the services' role could create one, and the migration refuses to run with one):

```bash
docker exec incident-postgres psql -U postgres -d incidentdb -tAc \
  "SELECT count(*) FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.member WHERE r.rolname = 'incident_app'"
# 0
docker exec incident-postgres psql -U incident_app -d incidentdb -c "COPY (SELECT 1) TO PROGRAM 'true'"
# ERROR:  permission denied to COPY to or from an external program
docker exec incident-postgres psql -U postgres -d incidentdb -tAc \
  "SELECT nspname FROM pg_namespace WHERE nspname NOT IN ('public', 'information_schema') AND nspname NOT LIKE 'pg\_%'"
# (no rows)
```

The docker-compose smoke test in CI runs all three checks ("Services' DB role is not a superuser").

## Migrating an existing database (keeping its data)

An existing data directory never runs the init script, so a database created before #0-78 keeps a
superuser `incident_app` until it is migrated by hand.

This is for a database that is **not** suspected of compromise. Before #0-78 a SQL injection had
superuser rights and could have left anything anywhere in the server, including outside this
database. Such a database is not migrated but restored from a trusted backup into a fresh volume
([Backup and restore](#backup-and-restore)).

**Why not just `ALTER ROLE incident_app NOSUPERUSER`?** Because in such a database `incident_app` is
the *bootstrap* role: the one `initdb` created from `POSTGRES_USER`. PostgreSQL never lets the
bootstrap role lose `SUPERUSER`:

```
ERROR:  permission denied to alter role
DETAIL:  The bootstrap user must have the SUPERUSER attribute.
```

So [`database-roles-migrate.sql`](database-roles-migrate.sql) does three things:
- renames the bootstrap role to the admin's name, and it becomes the admin;
- creates a new `incident_app` without superuser rights;
- moves the database, the schema and every object with an owner to the new role:
  - in `public`: tables (with their indexes and serial/identity sequences), partitions, foreign
    tables, other sequences, views, materialized views, functions, procedures, aggregates, enum, domain, range and
    composite types, collations, operators, operator families and classes, conversions, text search
    dictionaries and configurations, and extended statistics;
  - large objects.

Objects that belong to an extension stay with the admin.

Before committing, it checks that nothing in `public` still belongs to the admin, which catches a
kind of object it does not move, and that the database has no schema other than `public` (the
services use none; another one needs a decision by hand). If either check fails, it rolls back,
lists what it found and exits with code 3. It also sets the admin's `search_path` to `pg_catalog`,
as the init script does. Everything runs in **one transaction**: if anything fails, nothing has changed,
and the script can be run again once the cause is fixed.

The script takes everything from the database container's own environment:
- the admin's name and password (`POSTGRES_USER`, `POSTGRES_PASSWORD`);
- the services' role and its password (`APP_DB_USER`, `APP_DB_PASSWORD`);
- the database (`POSTGRES_DB`).

So the container is first recreated with the new configuration. Its volume is not empty, so the
init script does not run. No password is typed or passed on a command line, and the role's password
is by construction the one the services use.

**Tests.** The CI job "PostgreSQL Roles" runs the script on every build, on a seeded pre-#0-78 database with one
object of every kind it moves and a non-default admin name. It runs it five times:
- with a planted event trigger, and with an extra superuser: the preflight must stop it before any
  DDL, and the event trigger must not have fired;
- as a copy missing one kind, which its completeness check must stop and roll back;
- with an extra schema, which the same check must stop;
- for real.

The Kubernetes commands below have not been run against a cluster. The SQL is the same.

### docker-compose

```bash
# 0. Back up. Before the migration incident_app is still the bootstrap superuser; this is the
#    last time it acts as one.
docker exec incident-postgres pg_dump -U incident_app -d incidentdb -Fc > incidentdb-$(date +%F).dump

# 1. In docker/.env: rename POSTGRES_PASSWORD to DB_PASSWORD (same value, the password the services
#    use today; incident_secret by default) and add POSTGRES_ADMIN_PASSWORD (see .env.example).

# 2. Stop every service that connects to the database. A renamed role keeps its open sessions, so a
#    running connection pool would go on as the renamed superuser.
docker compose -f docker/docker-compose.yml stop \
  auth-service incident-service notification-service escalation-service postmortem-service oncall-service

# 3. Recreate the database container with the new configuration (same volume; the init script
#    does not run on it)
docker compose -f docker/docker-compose.yml up -d --wait postgres

# 4. A temporary superuser, created by the bootstrap role (a role cannot rename itself while
#    connected as itself)
docker exec incident-postgres psql -v ON_ERROR_STOP=1 -U incident_app -d incidentdb \
  -c "CREATE ROLE migration_admin LOGIN SUPERUSER"

# 5. The migration, as migration_admin
docker exec -i incident-postgres psql -U migration_admin -d incidentdb < docs/database-roles-migrate.sql

# 6. Drop the temporary superuser. Always, also if step 5 failed and you stop here. After a
#    failed step 5 nothing was renamed, so connect as incident_app instead of postgres.
docker exec incident-postgres psql -v ON_ERROR_STOP=1 -U postgres -d incidentdb -c "DROP ROLE migration_admin"

# 7. Verify: the checks in "Verify" above

# 8. Start the services
docker compose -f docker/docker-compose.yml up -d --wait \
  auth-service incident-service notification-service escalation-service postmortem-service oncall-service
```

**Step 5 output.** On success it prints, in order:
- `SET` and `BEGIN`;
- `ALTER ROLE` three times (rename, password, `search_path`);
- `CREATE ROLE`, `ALTER DATABASE` and `ALTER SCHEMA`;
- one `ALTER TABLE` (or `VIEW`, `ROUTINE`, `TYPE`, ...) per object;
- `COMMIT`;
- `Done: ...`.

On an error, or `Not migrated (...)` followed by `Migration aborted`, nothing was changed.
Fix the cause and run step 5 again.

### Kubernetes

The order differs, because `kubectl apply` also restores the Deployments' replica counts:

1. Back up (`kubectl exec -n <ns> postgresql-0 -- pg_dump -U incident_app -d incidentdb -Fc > ...`).
2. In the overlay's `secrets.yml`:
   - set `app-secrets` → `DB_PASSWORD` to the password the services use today. Before #0-78 no
     Deployment set it, so it is the default `application.yml` had then, `incident_secret`;
   - add the `postgresql-admin` Secret (`username`, a new `password`).
3. `kubectl apply -k k8s/overlays/<env>`. The StatefulSet restarts with the new environment, and the
   init script does not run on the existing PVC. The services keep working as before: they still
   reach the bootstrap `incident_app` with the same password.
4. Stop the six services that use the database (not ingestion-service). An HPA does not scale a
   Deployment up from 0:

   ```bash
   kubectl scale -n <ns> --replicas=0 \
     deployment/auth-service deployment/incident-service deployment/notification-service \
     deployment/escalation-service deployment/postmortem-service deployment/oncall-service
   ```

5. Steps 4–7 of the docker-compose list, with `kubectl exec -i -n <ns> postgresql-0 --` in place of
   `docker exec -i incident-postgres`. For example:
   `kubectl exec -i -n <ns> postgresql-0 -- psql -U migration_admin -d incidentdb < docs/database-roles-migrate.sql`.
6. `kubectl apply -k k8s/overlays/<env>` again. It restores the overlay's replica counts, and the
   HPAs take over from there.

## Changing a password later

The variables are applied only when the volume is first created. To change a password afterwards,
change it in the database **and** in its source: `docker/.env`, or the overlay's Secret. Then
restart whatever uses it.

Use psql's `\password`. It asks for the new password twice, hashes it on the client and sends only
the hash, so the clear text reaches neither the server log nor a command line:

```bash
docker exec -it incident-postgres psql -U postgres -d incidentdb
incidentdb=# \password incident_app     -- then DB_PASSWORD / app-secrets, and restart the six services
incidentdb=# \password postgres         -- then POSTGRES_ADMIN_PASSWORD / postgresql-admin; nothing restarts
```
