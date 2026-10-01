#!/usr/bin/env bash
# ============================================================
# PostgreSQL first-start script: the application role (backlog #0-78)
#
# Run once by the official postgres image, from /docker-entrypoint-initdb.d,
# when the data directory is empty. The same file is used by docker-compose
# (bind mount) and by Kubernetes (configMapGenerator in k8s/base): Kustomize
# cannot read files outside k8s/base, so it lives here and compose mounts it.
#
# Roles:
#   POSTGRES_USER  - the image's superuser. Administration only: no service
#                    connects with it. Its name is free; nothing else in the
#                    repository assumes one.
#   APP_DB_USER    - the role every service connects as (DB_USER). Not a
#                    superuser, cannot create roles or databases; it owns
#                    the database and the public schema, so Flyway can create
#                    tables and the trusted extensions the migrations need
#                    (btree_gist in oncall-service V4).
#
# Before #0-78 the image created incident_app itself through POSTGRES_USER,
# which made it a superuser, so SQL injection in any service was command
# execution in the database container (COPY ... PROGRAM), with access to the
# server's files and every role. Without superuser rights that is gone. What
# stays until backlog #0-67: as the owner of every table, the role is not
# bound by grants or by Row-Level Security (an owner bypasses RLS unless
# FORCE ROW LEVEL SECURITY is set), so one service can still read and change
# another service's tables; and an owner can plant a trigger, rule, default,
# view or function in public that runs with the rights of whoever fires it,
# so the superuser never touches anything outside pg_catalog in this
# database, not even to read it:
# service data, backups and restores go through an incident_app login
# (docs/database-roles.md, "Working as the admin"; SET ROLE is no boundary,
# planted code can RESET ROLE). The admin's search_path is pg_catalog as a
# second line of defence. #0-67 splits the role per service and separates the
# owner (migrations) from the runtime role.
#
# An existing data directory never runs this script. To move a database that
# already has data to this model, follow docs/database-roles.md.
# Tested by .github/scripts/test-postgres-roles.sh (CI job postgres-roles).
# ============================================================
set -euo pipefail

: "${POSTGRES_USER:?POSTGRES_USER must be set}"
: "${POSTGRES_DB:?POSTGRES_DB must be set}"
: "${APP_DB_USER:?APP_DB_USER must be set (the role the services connect as)}"
: "${APP_DB_PASSWORD:?APP_DB_PASSWORD must be set (the DB_PASSWORD of the services)}"

if [ "$APP_DB_USER" = "$POSTGRES_USER" ]; then
    echo "APP_DB_USER and POSTGRES_USER are both '$APP_DB_USER'." \
         "The application must not connect as the image's superuser (backlog #0-78)." >&2
    exit 1
fi

# Values reach psql as variables read from the environment with \getenv
# (psql 15+), never pasted into the SQL text and never on psql's command line,
# where `ps` would show the password. :"name" quotes an identifier and :'name'
# a literal, so a value cannot break out of the statement. If CREATE ROLE
# fails, the server log shows the statement with the password in clear; the
# volume is then discarded anyway (docs/database-roles.md).
psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" \
     --dbname "$POSTGRES_DB" <<'SQL'
\getenv admin_user POSTGRES_USER
\getenv app_user APP_DB_USER
\getenv app_password APP_DB_PASSWORD
\getenv db_name POSTGRES_DB
CREATE ROLE :"app_user" LOGIN
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    PASSWORD :'app_password';

-- Owner of the database and of the public schema: enough for Flyway to
-- create and alter its tables and to create trusted extensions, nothing more.
ALTER DATABASE :"db_name" OWNER TO :"app_user";
ALTER SCHEMA public OWNER TO :"app_user";

-- The admin's sessions resolve unqualified names in pg_catalog only. public
-- belongs to the services' role, so anything there (a function or operator
-- shadowing a built-in, the CVE-2018-1058 pattern) could have been planted
-- through a service; the admin names public objects explicitly instead.
ALTER ROLE :"admin_user" SET search_path = pg_catalog;

-- Extensions available to every service from the start.
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";
SQL

echo "Created application role '$APP_DB_USER' (not a superuser) owning database '$POSTGRES_DB'."
