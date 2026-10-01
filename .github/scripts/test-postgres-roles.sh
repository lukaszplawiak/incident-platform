#!/usr/bin/env bash
# ============================================================
# Tests the PostgreSQL role setup of backlog #0-78 against the real image:
#   0. docker-compose refuses to start without the two database passwords
#   1. k8s/base/infrastructure/postgresql-init.sh on an empty volume, and why
#      readiness must go over TCP (the init-phase server is socket-only)
#   2. its guard against APP_DB_USER == POSTGRES_USER
#      and why the admin never touches service objects: a planted trigger
#      escapes SET ROLE in an admin session, not in an incident_app login
#   3. docs/database-roles-migrate.sql on a database created before #0-78:
#      its preflight (event trigger, extra superuser) and completeness checks
#      (a kind it would miss, an extra schema), each of which must abort and
#      roll back, then the real run; and a backup/restore round trip as
#      incident_app with the guide's commands
#
# Run by the postgres-roles job in .github/workflows/ci.yml; locally:
#   .github/scripts/test-postgres-roles.sh   (needs Docker)
#
# The image is read from docker/docker-compose.yml, so the test always runs
# the version compose and Kubernetes use. Passwords are checked over a Docker
# network: inside the container, the socket and 127.0.0.1 use 'trust'
# authentication and would accept any password.
# ============================================================
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
INIT_SCRIPT="$REPO_ROOT/k8s/base/infrastructure/postgresql-init.sh"
MIGRATION="$REPO_ROOT/docs/database-roles-migrate.sql"
IMAGE=$(awk '/^  postgres:/{f=1} f && /image:/{print $2; exit}' "$REPO_ROOT/docker/docker-compose.yml")
[ -n "$IMAGE" ] || { echo "::error::could not read the postgres image from docker/docker-compose.yml"; exit 1; }

PREFIX="pgroles-test-$$"
NET="$PREFIX-net"
VOL="$PREFIX-vol"
# A hostile password: quotes, a statement separator and a comment.
APP_PW="p'q\";DROP ROLE x;--"

failures=0
fail() { echo "::error::$*"; failures=$((failures + 1)); }
pass() { echo "  ok: $*"; }

cleanup() {
    docker rm -f "$PREFIX-fresh" "$PREFIX-guard" "$PREFIX-old" "$PREFIX-new" "$PREFIX-restore" >/dev/null 2>&1 || true
    rm -rf "$SLOW_INIT_DIR"
    docker volume rm "$VOL" >/dev/null 2>&1 || true
    docker network rm "$NET" >/dev/null 2>&1 || true
}
SLOW_INIT_DIR=$(mktemp -d)
# mktemp -d is 0700; the image reads its init directory as the postgres user
# (uid 999), which on a Linux bind mount could not even list a 0700 directory.
chmod 755 "$SLOW_INIT_DIR"
trap cleanup EXIT

# Waits until the final server answers over TCP (the init-phase server is
# socket-only), or fails after 90 s.
wait_ready() {
    local container=$1 user=$2
    for _ in $(seq 1 90); do
        if docker exec "$container" pg_isready -h 127.0.0.1 -U "$user" -d incidentdb >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    docker logs "$container" || true
    echo "::error::$container did not become ready"
    exit 1
}

# psql inside the container, over the local socket (trust).
# -i: SQL can also come on stdin (a heredoc).
q() { local container=$1 user=$2; shift 2; docker exec -i "$container" psql -v ON_ERROR_STOP=1 -U "$user" -d incidentdb -tAq "$@"; }

# grep on a captured string: `docker logs | grep -q` fails under pipefail
# when grep exits early and docker logs gets SIGPIPE.
logs_contain() { local logs; logs=$(docker logs "$1" 2>&1); grep -q "$2" <<<"$logs"; }

# psql from another container over the network (password authentication).
net_q() {
    local host=$1 user=$2 password=$3; shift 3
    docker run --rm --network "$NET" -e PGPASSWORD="$password" "$IMAGE" \
        psql -v ON_ERROR_STOP=1 -h "$host" -U "$user" -d incidentdb -tAq "$@"
}

# The services' role: no superuser-like attribute, no role membership,
# COPY ... TO PROGRAM refused.
assert_app_role_restricted() {
    local container=$1 admin=$2 app=$3
    local attrs members out
    attrs=$(q "$container" "$admin" -c "SELECT concat_ws(',', rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls) FROM pg_roles WHERE rolname = '$app'")
    [ "$attrs" = "f,f,f,f,f" ] && pass "$app has no superuser-like attribute" || fail "$app attributes: '$attrs'"
    members=$(q "$container" "$admin" -c "SELECT count(*) FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.member WHERE r.rolname = '$app'")
    [ "$members" = "0" ] && pass "$app is a member of no role" || fail "$app is a member of $members role(s)"
    if out=$(q "$container" "$app" -c "COPY (SELECT 1) TO PROGRAM 'true'" 2>&1); then
        fail "$app was allowed to run COPY ... TO PROGRAM"
    elif grep -q "permission denied to COPY to or from an external program" <<<"$out"; then
        pass "COPY ... TO PROGRAM refused for $app"
    else
        fail "COPY ... TO PROGRAM failed for another reason: $out"
    fi
}

# The admin's sessions resolve unqualified names in pg_catalog only, so an
# object planted in public through a service cannot shadow a built-in or be
# picked up by an unqualified admin query.
assert_admin_search_path() {
    local container=$1 admin=$2 out
    out=$(q "$container" "$admin" -c "SHOW search_path")
    [ "$out" = "pg_catalog" ] && pass "$admin's search_path is pg_catalog" || fail "$admin's search_path is '$out'"
    if out=$(q "$container" "$admin" -c "SELECT count(*) FROM t_probe" 2>&1); then
        fail "$admin resolved an unqualified name in public"
    elif grep -q 'relation "t_probe" does not exist' <<<"$out"; then
        pass "$admin does not resolve unqualified names in public"
    else
        fail "unexpected error for an unqualified name: $out"
    fi
}

docker network create "$NET" >/dev/null
echo "Image: $IMAGE"

# ------------------------------------------------------------
echo "0. docker-compose refuses to start without the database passwords"
for var in DB_PASSWORD POSTGRES_ADMIN_PASSWORD; do
    set +e
    out=$(cd /tmp && env DB_PASSWORD=x POSTGRES_ADMIN_PASSWORD=x \
          env -u "$var" docker compose -f "$REPO_ROOT/docker/docker-compose.yml" --env-file /dev/null config --quiet 2>&1)
    rc=$?
    set -e
    if [ "$rc" -ne 0 ] && grep -q "required variable $var is missing a value: set $var in docker/.env" <<<"$out"; then
        pass "compose stops without $var, with the fix in the message"
    else
        fail "compose without $var: exit $rc, $out"
    fi
done

# ------------------------------------------------------------
echo "1. Fresh volume: init script creates a restricted services' role"
# A non-default admin name proves nothing depends on the name 'postgres'.
# A 10 s pause before the role script widens the init phase (wide enough for
# a slow runner's 1 s polling), to show why the readiness checks go over TCP:
# during init the socket answers, TCP does not.
cp "$INIT_SCRIPT" "$SLOW_INIT_DIR/10-app-role.sh"
printf '#!/bin/sh\nsleep 10\n' > "$SLOW_INIT_DIR/05-pause.sh"
chmod 755 "$SLOW_INIT_DIR"/*.sh
docker run -d --name "$PREFIX-fresh" --network "$NET" \
    -e POSTGRES_DB=incidentdb -e POSTGRES_USER=dbadmin -e POSTGRES_PASSWORD=admin-pw \
    -e APP_DB_USER=incident_app -e APP_DB_PASSWORD="$APP_PW" \
    -v "$SLOW_INIT_DIR:/docker-entrypoint-initdb.d:ro" "$IMAGE" >/dev/null
socket_early=no
tcp_early=no
for _ in $(seq 1 90); do
    role=$(docker exec "$PREFIX-fresh" psql -U dbadmin -d incidentdb -tAc "SELECT count(*) FROM pg_roles WHERE rolname = 'incident_app'" 2>/dev/null || true)
    if [ "$role" = "0" ]; then
        docker exec "$PREFIX-fresh" pg_isready -U dbadmin -d incidentdb >/dev/null 2>&1 && socket_early=yes
        docker exec "$PREFIX-fresh" pg_isready -h 127.0.0.1 -U dbadmin -d incidentdb >/dev/null 2>&1 && tcp_early=yes
    fi
    docker exec "$PREFIX-fresh" pg_isready -h 127.0.0.1 -U dbadmin -d incidentdb >/dev/null 2>&1 && break
    sleep 1
done
wait_ready "$PREFIX-fresh" dbadmin
[ "$socket_early" = "yes" ] && pass "during init the socket reports ready before the role exists (why probes use TCP)" \
    || fail "the socket never reported ready during init; the TCP-probe rationale no longer holds, re-check it"
[ "$tcp_early" = "no" ] && pass "TCP reports ready only after the role exists" || fail "TCP reported ready before the role existed"
logs_contain "$PREFIX-fresh" "Created application role 'incident_app'" \
    && pass "init script ran" || fail "init script did not report success"
assert_app_role_restricted "$PREFIX-fresh" dbadmin incident_app
[ "$(q "$PREFIX-fresh" dbadmin -c "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = 'incidentdb'")" = "incident_app" ] \
    && pass "incident_app owns the database" || fail "incident_app does not own the database"
[ "$(net_q "$PREFIX-fresh" incident_app "$APP_PW" -c "SELECT current_user")" = "incident_app" ] \
    && pass "login with the hostile password works over the network" || fail "login with the configured password failed"
if net_q "$PREFIX-fresh" incident_app wrong -c "SELECT 1" >/dev/null 2>&1; then
    fail "a wrong password was accepted"
else
    pass "a wrong password is rejected"
fi
net_q "$PREFIX-fresh" incident_app "$APP_PW" -c "CREATE TABLE t_probe (id int)" >/dev/null
assert_admin_search_path "$PREFIX-fresh" dbadmin

# Why service data is touched only by logging in as incident_app, never as the
# admin (docs/database-roles.md, "Working as the admin"): the services' role
# can plant a trigger. Fired in a superuser session, even under SET ROLE, the
# trigger can RESET ROLE and act as the superuser; fired in an incident_app
# login, RESET ROLE only returns to incident_app.
q "$PREFIX-fresh" incident_app <<'SQL'
CREATE TABLE t_trap (id int);
CREATE FUNCTION f_trap() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RESET ROLE;
    CREATE ROLE planted_superuser SUPERUSER;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_trap BEFORE INSERT ON t_trap FOR EACH ROW EXECUTE FUNCTION f_trap();
SQL
q "$PREFIX-fresh" dbadmin -c "SET ROLE incident_app" -c "INSERT INTO public.t_trap VALUES (1)" >/dev/null 2>&1 || true
if [ "$(q "$PREFIX-fresh" dbadmin -c "SELECT count(*) FROM pg_roles WHERE rolname = 'planted_superuser'")" = "1" ]; then
    pass "a planted trigger escapes SET ROLE in a superuser session (why the admin never touches public)"
    q "$PREFIX-fresh" dbadmin -c "DROP ROLE planted_superuser"
else
    fail "the planted trigger did not escape SET ROLE; the guide's rationale no longer holds, re-check it"
fi
if out=$(q "$PREFIX-fresh" incident_app -c "INSERT INTO public.t_trap VALUES (2)" 2>&1); then
    fail "the planted trigger ran as incident_app without an error"
elif grep -q "permission denied to create role" <<<"$out" \
     && [ "$(q "$PREFIX-fresh" dbadmin -c "SELECT count(*) FROM pg_roles WHERE rolname = 'planted_superuser'")" = "0" ]; then
    pass "in an incident_app login the same trigger cannot create a role"
else
    fail "unexpected result of the planted trigger as incident_app: $out"
fi
q "$PREFIX-fresh" incident_app -c "DROP TABLE t_trap" -c "DROP FUNCTION f_trap()"
net_q "$PREFIX-fresh" incident_app "$APP_PW" -c "CREATE EXTENSION IF NOT EXISTS btree_gist" >/dev/null \
    && pass "incident_app can create a trusted extension (btree_gist, oncall V4)" || fail "incident_app cannot create btree_gist"

# ------------------------------------------------------------
echo "2. Guard: APP_DB_USER equal to POSTGRES_USER is refused"
docker run -d --name "$PREFIX-guard" \
    -e POSTGRES_DB=incidentdb -e POSTGRES_USER=incident_app -e POSTGRES_PASSWORD=x \
    -e APP_DB_USER=incident_app -e APP_DB_PASSWORD=y \
    -v "$INIT_SCRIPT:/docker-entrypoint-initdb.d/10-app-role.sh:ro" "$IMAGE" >/dev/null
guard_exit=timeout
for _ in $(seq 1 90); do
    if [ "$(docker inspect -f '{{.State.Running}}' "$PREFIX-guard")" = "false" ]; then
        guard_exit=$(docker inspect -f '{{.State.ExitCode}}' "$PREFIX-guard")
        break
    fi
    sleep 1
done
if [ "$guard_exit" != "0" ] && [ "$guard_exit" != "timeout" ] \
   && logs_contain "$PREFIX-guard" "must not connect as the image's superuser"; then
    pass "init stopped (exit $guard_exit) with the guard's message"
else
    fail "guard did not stop the init (exit: $guard_exit)"
fi

# ------------------------------------------------------------
echo "3. Migration of a database created before #0-78"
# Pre-#0-78 setup: the services' role is POSTGRES_USER, the bootstrap superuser.
docker run -d --name "$PREFIX-old" -v "$VOL:/var/lib/postgresql/data" \
    -e POSTGRES_DB=incidentdb -e POSTGRES_USER=incident_app -e POSTGRES_PASSWORD=incident_secret "$IMAGE" >/dev/null
wait_ready "$PREFIX-old" incident_app
# One object of every kind the migration handles, plus extensions it must skip.
q "$PREFIX-old" incident_app <<'SQL'
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE TABLE t_serial (id bigserial PRIMARY KEY, v text);
CREATE TABLE t_identity (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY);
CREATE TABLE t_parted (id int, at date) PARTITION BY RANGE (at);
CREATE TABLE t_parted_2026 PARTITION OF t_parted FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE SEQUENCE s_standalone;
CREATE VIEW v_serial AS SELECT * FROM t_serial;
CREATE MATERIALIZED VIEW mv_serial AS SELECT * FROM t_serial;
CREATE FUNCTION f_one() RETURNS int LANGUAGE sql AS 'SELECT 1';
CREATE AGGREGATE agg_sum(int) (SFUNC = int4pl, STYPE = int);
CREATE TYPE e_mood AS ENUM ('ok');
CREATE DOMAIN d_positive AS int CHECK (VALUE > 0);
CREATE TYPE r_float AS RANGE (subtype = float8);
CREATE TYPE c_pair AS (a int, b int);
CREATE COLLATION coll_c (locale = 'C');
CREATE FUNCTION f_eq(int, int) RETURNS bool LANGUAGE sql IMMUTABLE AS 'SELECT $1 = $2';
CREATE OPERATOR === (LEFTARG = int, RIGHTARG = int, FUNCTION = f_eq);
CREATE OPERATOR FAMILY opf_int USING btree;
CREATE OPERATOR CLASS opc_int FOR TYPE int USING btree FAMILY opf_int AS OPERATOR 1 <, FUNCTION 1 btint4cmp(int, int);
CREATE EXTENSION IF NOT EXISTS file_fdw;
CREATE SERVER srv_file FOREIGN DATA WRAPPER file_fdw;
CREATE FOREIGN TABLE ft_file (a int) SERVER srv_file OPTIONS (filename '/dev/null');
CREATE TABLE t_probe (id int);
CREATE CONVERSION conv_latin1 FOR 'LATIN1' TO 'UTF8' FROM iso8859_1_to_utf8;
CREATE TEXT SEARCH DICTIONARY tsd_simple (TEMPLATE = simple);
CREATE TEXT SEARCH CONFIGURATION tsc_simple (COPY = simple);
CREATE STATISTICS st_serial (ndistinct) ON id, v FROM t_serial;
DO $$ BEGIN PERFORM lo_create(424242); END $$;
INSERT INTO t_serial (v) VALUES ('kept');
SQL
# The seed must really be there, or every later "nothing left" check passes vacuously.
seeded=$(q "$PREFIX-old" incident_app -c "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname IN ('t_serial', 't_identity', 't_parted', 't_parted_2026', 's_standalone', 'v_serial', 'mv_serial')")
[ "$seeded" = "7" ] || { echo "::error::seeding the pre-#0-78 database failed ($seeded of 7 relations)"; exit 1; }
# Stopped cleanly, as an operator recreating the container would.
docker stop "$PREFIX-old" >/dev/null
docker rm "$PREFIX-old" >/dev/null

# Recreated with the new configuration on the same volume: the init script is
# mounted but must not run, as the volume is not empty.
docker run -d --name "$PREFIX-new" --network "$NET" -v "$VOL:/var/lib/postgresql/data" \
    -e POSTGRES_DB=incidentdb -e POSTGRES_USER=dbadmin -e POSTGRES_PASSWORD=new-admin-pw \
    -e APP_DB_USER=incident_app -e APP_DB_PASSWORD="$APP_PW" \
    -v "$INIT_SCRIPT:/docker-entrypoint-initdb.d/10-app-role.sh:ro" "$IMAGE" >/dev/null
wait_ready "$PREFIX-new" incident_app
logs_contain "$PREFIX-new" "Created application role" \
    && fail "init script ran on a non-empty volume" || pass "init script skipped on the existing volume"

q "$PREFIX-new" incident_app -c "CREATE ROLE migration_admin LOGIN SUPERUSER"

# 3a. The completeness check is the safety net for a kind the script does not
#     move. A copy of the migration without its routine branch must be stopped
#     by it, and the abort must leave the database exactly as it was.
broken=$(awk '/^SELECT format\(.ALTER ROUTINE/{skip=1} skip && /^UNION ALL$/{skip=0; next} !skip' "$MIGRATION")
grep -q "ALTER ROUTINE" <<<"$broken" && { echo "::error::could not remove the routine branch for the abort test"; exit 1; }
set +e
out=$(docker exec -i "$PREFIX-new" psql -U migration_admin -d incidentdb <<<"$broken" 2>&1)
rc=$?
set -e
if [ "$rc" -eq 3 ] && grep -q "Not migrated (still owned by the admin, or outside public):.*routine public.f_one()" <<<"$out" \
   && grep -q "Migration aborted, nothing was changed" <<<"$out" \
   && [ "$(q "$PREFIX-new" migration_admin -c "SELECT rolname FROM pg_roles WHERE oid = 10")" = "incident_app" ] \
   && [ "$(q "$PREFIX-new" migration_admin -c "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE relname = 't_serial'")" = "incident_app" ] \
   && [ "$(q "$PREFIX-new" migration_admin -c "SELECT count(*) FROM pg_roles WHERE rolname = 'dbadmin'")" = "0" ]; then
    pass "completeness check named the routines, aborted (exit 3) and rolled everything back"
else
    fail "migration missing a branch did not abort cleanly (exit $rc): $out"
fi

# 3a2. A schema other than public is not migrated; it must stop the migration.
q "$PREFIX-new" incident_app -c "CREATE SCHEMA s_extra"
set +e
out=$(docker exec -i "$PREFIX-new" psql -U migration_admin -d incidentdb < "$MIGRATION" 2>&1)
rc=$?
set -e
if [ "$rc" -eq 3 ] && grep -q "schema s_extra (only public is migrated" <<<"$out" \
   && [ "$(q "$PREFIX-new" migration_admin -c "SELECT rolname FROM pg_roles WHERE oid = 10")" = "incident_app" ]; then
    pass "a schema other than public stops the migration (exit 3), nothing changed"
else
    fail "migration with an extra schema did not abort cleanly (exit $rc): $out"
fi
q "$PREFIX-new" incident_app -c "DROP SCHEMA s_extra"

# 3a3. Preflight: an event trigger fires on DDL as the migrating superuser, and
#      an extra superuser would survive the migration. Either must stop it
#      before any DDL runs: the planted event trigger must not have fired.
q "$PREFIX-new" incident_app <<'SQL'
CREATE TABLE evt_log (tag text);
CREATE FUNCTION evt_spy() RETURNS event_trigger LANGUAGE plpgsql AS $$
BEGIN INSERT INTO public.evt_log VALUES (tg_tag); END $$;
CREATE EVENT TRIGGER evt_spy ON ddl_command_end EXECUTE FUNCTION evt_spy();
SQL
set +e
out=$(docker exec -i "$PREFIX-new" psql -U migration_admin -d incidentdb < "$MIGRATION" 2>&1)
rc=$?
set -e
if [ "$rc" -eq 3 ] && grep -q "inspect the database by hand first:.*event trigger evt_spy" <<<"$out" \
   && [ "$(q "$PREFIX-new" migration_admin -c "SELECT count(*) FROM public.evt_log")" = "0" ] \
   && [ "$(q "$PREFIX-new" migration_admin -c "SELECT rolname FROM pg_roles WHERE oid = 10")" = "incident_app" ]; then
    pass "an event trigger stops the migration before any DDL (it never fired), nothing changed"
else
    fail "migration with an event trigger did not abort cleanly (exit $rc): $out"
fi
q "$PREFIX-new" incident_app -c "DROP EVENT TRIGGER evt_spy" -c "DROP FUNCTION evt_spy()" -c "DROP TABLE evt_log"
q "$PREFIX-new" incident_app -c "CREATE ROLE extra_superuser SUPERUSER"
set +e
out=$(docker exec -i "$PREFIX-new" psql -U migration_admin -d incidentdb < "$MIGRATION" 2>&1)
rc=$?
set -e
if [ "$rc" -eq 3 ] && grep -q "inspect the database by hand first:.*superuser extra_superuser" <<<"$out" \
   && [ "$(q "$PREFIX-new" migration_admin -c "SELECT rolname FROM pg_roles WHERE oid = 10")" = "incident_app" ]; then
    pass "an extra superuser stops the migration, nothing changed"
else
    fail "migration with an extra superuser did not abort cleanly (exit $rc): $out"
fi
q "$PREFIX-new" incident_app -c "DROP ROLE extra_superuser"

# 3b. The real run.
if out=$(docker exec -i "$PREFIX-new" psql -U migration_admin -d incidentdb < "$MIGRATION" 2>&1) \
   && grep -q "^Done:" <<<"$out"; then
    pass "migration completed"
else
    fail "migration failed: $out"
fi
q "$PREFIX-new" dbadmin -c "DROP ROLE migration_admin"

[ "$(q "$PREFIX-new" dbadmin -c "SELECT rolname || ',' || rolsuper FROM pg_roles WHERE oid = 10")" = "dbadmin,true" ] \
    && pass "the bootstrap role is now the admin 'dbadmin'" || fail "the bootstrap role was not renamed"
assert_app_role_restricted "$PREFIX-new" dbadmin incident_app
owners=$(q "$PREFIX-new" dbadmin <<'SQL'
SELECT string_agg(obj || '=' || owner, ' ' ORDER BY obj) FROM (
    SELECT c.relname AS obj, pg_get_userbyid(c.relowner) AS owner FROM pg_class c
    WHERE c.relname IN ('t_serial', 't_serial_id_seq', 't_identity', 't_identity_id_seq', 't_parted',
                        't_parted_2026', 's_standalone', 'v_serial', 'mv_serial', 'ft_file')
    UNION ALL SELECT p.proname, pg_get_userbyid(p.proowner) FROM pg_proc p WHERE p.proname IN ('f_one', 'agg_sum', 'f_eq')
    UNION ALL SELECT t.typname, pg_get_userbyid(t.typowner) FROM pg_type t
              WHERE t.typname IN ('e_mood', 'd_positive', 'r_float', 'c_pair')
    UNION ALL SELECT collname, pg_get_userbyid(collowner) FROM pg_collation WHERE collname = 'coll_c'
    UNION ALL SELECT oprname, pg_get_userbyid(oprowner) FROM pg_operator WHERE oprname = '==='
    UNION ALL SELECT opfname, pg_get_userbyid(opfowner) FROM pg_opfamily WHERE opfname = 'opf_int'
    UNION ALL SELECT opcname, pg_get_userbyid(opcowner) FROM pg_opclass WHERE opcname = 'opc_int'
    UNION ALL SELECT conname, pg_get_userbyid(conowner) FROM pg_conversion WHERE conname = 'conv_latin1'
    UNION ALL SELECT dictname, pg_get_userbyid(dictowner) FROM pg_ts_dict WHERE dictname = 'tsd_simple'
    UNION ALL SELECT cfgname, pg_get_userbyid(cfgowner) FROM pg_ts_config WHERE cfgname = 'tsc_simple'
    UNION ALL SELECT stxname, pg_get_userbyid(stxowner) FROM pg_statistic_ext WHERE stxname = 'st_serial'
    UNION ALL SELECT 'lo_' || oid, pg_get_userbyid(lomowner) FROM pg_largeobject_metadata WHERE oid = 424242
) x
SQL
)
not_moved=$(tr ' ' '\n' <<<"$owners" | grep -v '=incident_app$' || true)
moved=$(tr ' ' '\n' <<<"$owners" | grep -c '=incident_app$' || true)
if [ -z "$not_moved" ] && [ "$moved" = "26" ]; then
    pass "all 26 seeded objects of every kind now belong to incident_app"
else
    fail "ownership after migration ($moved of 26 moved): ${not_moved:-some seeded objects are missing}"
fi
ext_owner=$(q "$PREFIX-new" dbadmin -c "SELECT DISTINCT pg_get_userbyid(p.proowner) FROM pg_proc p JOIN pg_depend d ON d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e' JOIN pg_extension e ON e.oid = d.refobjid WHERE e.extname = 'pg_trgm'")
[ "$ext_owner" = "dbadmin" ] && pass "extension members stay with the admin" || fail "extension members owned by '$ext_owner'"
assert_admin_search_path "$PREFIX-new" dbadmin
[ "$(net_q "$PREFIX-new" incident_app "$APP_PW" -c "INSERT INTO t_serial (v) VALUES ('new'); SELECT string_agg(v, ',' ORDER BY id) FROM t_serial")" = "kept,new" ] \
    && pass "data kept, and the new role writes through the moved sequence" || fail "data or sequence check failed"
[ "$(net_q "$PREFIX-new" dbadmin new-admin-pw -c "SELECT current_user")" = "dbadmin" ] \
    && pass "the admin logs in with the new password" || fail "admin login with the new password failed"

# ------------------------------------------------------------
echo "4. Backup and restore as incident_app, with the guide's commands"
# The fresh database from section 1: the platform's shape (only trusted
# extensions), some data, a sequence.
q "$PREFIX-fresh" incident_app -c "INSERT INTO t_probe SELECT generate_series(1, 3)"
docker exec "$PREFIX-fresh" pg_dump -U incident_app -d incidentdb -Fc > "$SLOW_INIT_DIR/backup.dump"
docker run -d --name "$PREFIX-restore" \
    -e POSTGRES_DB=incidentdb -e POSTGRES_USER=dbadmin -e POSTGRES_PASSWORD=admin-pw \
    -e APP_DB_USER=incident_app -e APP_DB_PASSWORD="$APP_PW" \
    -v "$INIT_SCRIPT:/docker-entrypoint-initdb.d/10-app-role.sh:ro" "$IMAGE" >/dev/null
wait_ready "$PREFIX-restore" dbadmin
docker cp "$SLOW_INIT_DIR/backup.dump" "$PREFIX-restore:/tmp/incidentdb.dump"
set +e
out=$(docker exec "$PREFIX-restore" sh -c '
  pg_restore -l /tmp/incidentdb.dump | grep -v " COMMENT - EXTENSION " > /tmp/toc.list &&
  pg_restore -U incident_app -d incidentdb --exit-on-error --no-owner -L /tmp/toc.list /tmp/incidentdb.dump' 2>&1)
rc=$?
set -e
if [ "$rc" -eq 0 ] && [ "$(q "$PREFIX-restore" incident_app -c "SELECT count(*) FROM t_probe")" = "3" ] \
   && [ "$(q "$PREFIX-restore" dbadmin -c "SELECT string_agg(DISTINCT pg_get_userbyid(c.relowner), ',') FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'")" = "incident_app" ]; then
    pass "restore as incident_app: exit 0, data back, everything owned by incident_app"
else
    fail "restore as incident_app failed (exit $rc): $out"
fi

echo
if [ "$failures" -ne 0 ]; then
    echo "::error::$failures check(s) failed"
    exit 1
fi
echo "All PostgreSQL role checks passed."
