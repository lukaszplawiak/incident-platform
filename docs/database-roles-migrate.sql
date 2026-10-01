-- ============================================================
-- Moves a database created before backlog #0-78 to the role model in
-- docs/database-roles.md, keeping its data. Run as a temporary superuser
-- (migration_admin), inside the database container, after the container was
-- recreated with the new configuration. Full procedure: docs/database-roles.md.
--
-- In such a database the services' role is the bootstrap role (OID 10),
-- which can never lose SUPERUSER. So it is renamed to the admin's name, and a
-- new services' role is created without superuser rights, owning everything
-- the old one owned in the public schema.
--
-- Everything comes from the database container's own environment, which
-- already has the new configuration: POSTGRES_USER / POSTGRES_PASSWORD (the
-- admin), APP_DB_USER / APP_DB_PASSWORD (the services' role, the same values
-- the services use) and POSTGRES_DB. Nothing is typed or passed on a command
-- line. Tested by .github/scripts/test-postgres-roles.sh in CI.
-- ============================================================
\set ON_ERROR_STOP on
-- This session is a superuser working on objects the services' role owns,
-- which could have been changed through a SQL injection. It only runs
-- catalog queries and ALTER ... OWNER (no DML, so no trigger, rule, default or
-- constraint fires), and with search_path = pg_catalog no unqualified name can
-- resolve to something planted in public: every object below is named with its
-- schema. The one kind of planted code that DDL does fire is an event trigger;
-- the preflight check below refuses to run while one exists.
--
-- For a database that is NOT suspected of compromise. Before #0-78 the
-- services' role was a superuser, so a SQL injection then could have left
-- anything anywhere; such a database is restored from a trusted backup, not
-- migrated.
SET search_path = pg_catalog;
\getenv admin_user POSTGRES_USER
\getenv admin_password POSTGRES_PASSWORD
\getenv app_user APP_DB_USER
\getenv app_password APP_DB_PASSWORD
\getenv db_name POSTGRES_DB

-- One transaction: if anything fails, nothing has changed and the script can
-- simply be run again once the cause is fixed.
BEGIN;

-- Preflight, before any DDL: event triggers fire on DDL (ALTER ... OWNER
-- included) with this session's superuser rights, and a superuser other than
-- the bootstrap role and this session's would survive the migration. Only a
-- superuser can create either, and the platform creates neither, so either
-- one means the database needs a person's look before anything runs here.
SELECT count(*) > 0 AS preflight_failed,
       coalesce(string_agg(obj, ', '), '') AS preflight_findings
FROM (
    SELECT format('event trigger %I', evtname) AS obj FROM pg_event_trigger
    UNION ALL
    SELECT format('superuser %I', rolname)
    FROM pg_roles
    WHERE rolsuper AND oid <> 10 AND rolname <> current_user
) found
\gset
\if :preflight_failed
    \echo 'Not migrated, inspect the database by hand first:' :preflight_findings
    ROLLBACK;
    DO $$ BEGIN RAISE EXCEPTION 'Migration aborted, nothing was changed (see the findings listed above)'; END $$;
\endif

-- The bootstrap role keeps SUPERUSER for good; it becomes the admin.
ALTER ROLE :"app_user" RENAME TO :"admin_user";
ALTER ROLE :"admin_user" PASSWORD :'admin_password';
-- As in postgresql-init.sh: the admin resolves unqualified names in
-- pg_catalog only, never in public, which the services' role owns.
ALTER ROLE :"admin_user" SET search_path = pg_catalog;

-- A new services' role, without superuser rights.
CREATE ROLE :"app_user" LOGIN
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    PASSWORD :'app_password';

ALTER DATABASE :"db_name" OWNER TO :"app_user";
ALTER SCHEMA public OWNER TO :"app_user";

-- The admin's OID, looked up by name (not ::regrole, which would parse the
-- name as an identifier and fold or split it).
SELECT oid AS admin_oid FROM pg_roles WHERE rolname = :'admin_user'
\gset

-- Every object that the bootstrap role owned in public, except objects that
-- belong to an extension (those stay with the admin). Indexes, TOAST tables,
-- array and multirange types and a table's row type follow their owner and are
-- not listed. A sequence owned by a table column (serial/identity) follows its
-- table; partitions are listed, as ALTER TABLE does not recurse into them.
-- Large objects are not in a schema; the bootstrap role owned all of them.
SELECT format('ALTER %s %I.%I OWNER TO %I',
              CASE c.relkind WHEN 'r' THEN 'TABLE' WHEN 'p' THEN 'TABLE'
                             WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW'
                             WHEN 'S' THEN 'SEQUENCE' WHEN 'f' THEN 'FOREIGN TABLE' END,
              n.nspname, c.relname, :'app_user')
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public'
  AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
  AND c.relowner = :admin_oid
  AND NOT EXISTS (SELECT 1 FROM pg_depend d
                  WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e')
  AND NOT (c.relkind = 'S' AND EXISTS (SELECT 1 FROM pg_depend d
                  WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype IN ('a', 'i')))
UNION ALL
SELECT format('ALTER ROUTINE %s OWNER TO %I', p.oid::regprocedure, :'app_user')
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'public' AND p.proowner = :admin_oid
  AND NOT EXISTS (SELECT 1 FROM pg_depend d
                  WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e')
UNION ALL
SELECT format('ALTER TYPE %I.%I OWNER TO %I', n.nspname, t.typname, :'app_user')
FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
WHERE n.nspname = 'public' AND t.typowner = :admin_oid
  AND (t.typtype IN ('e', 'd', 'r')
       OR (t.typtype = 'c' AND (SELECT relkind FROM pg_class WHERE oid = t.typrelid) = 'c'))
  AND NOT EXISTS (SELECT 1 FROM pg_depend d
                  WHERE d.classid = 'pg_type'::regclass AND d.objid = t.oid AND d.deptype = 'e')
UNION ALL
SELECT format('ALTER COLLATION %I.%I OWNER TO %I', n.nspname, co.collname, :'app_user')
FROM pg_collation co JOIN pg_namespace n ON n.oid = co.collnamespace
WHERE n.nspname = 'public' AND co.collowner = :admin_oid
  AND NOT EXISTS (SELECT 1 FROM pg_depend d
                  WHERE d.classid = 'pg_collation'::regclass AND d.objid = co.oid AND d.deptype = 'e')
UNION ALL
SELECT format('ALTER OPERATOR %s OWNER TO %I', o.oid::regoperator, :'app_user')
FROM pg_operator o JOIN pg_namespace n ON n.oid = o.oprnamespace
WHERE n.nspname = 'public' AND o.oprowner = :admin_oid
  AND NOT EXISTS (SELECT 1 FROM pg_depend d
                  WHERE d.classid = 'pg_operator'::regclass AND d.objid = o.oid AND d.deptype = 'e')
UNION ALL
SELECT format('ALTER OPERATOR FAMILY %I.%I USING %I OWNER TO %I', n.nspname, f.opfname, am.amname, :'app_user')
FROM pg_opfamily f JOIN pg_namespace n ON n.oid = f.opfnamespace JOIN pg_am am ON am.oid = f.opfmethod
WHERE n.nspname = 'public' AND f.opfowner = :admin_oid
  AND NOT EXISTS (SELECT 1 FROM pg_depend d
                  WHERE d.classid = 'pg_opfamily'::regclass AND d.objid = f.oid AND d.deptype = 'e')
UNION ALL
SELECT format('ALTER OPERATOR CLASS %I.%I USING %I OWNER TO %I', n.nspname, oc.opcname, am.amname, :'app_user')
FROM pg_opclass oc JOIN pg_namespace n ON n.oid = oc.opcnamespace JOIN pg_am am ON am.oid = oc.opcmethod
WHERE n.nspname = 'public' AND oc.opcowner = :admin_oid
  AND NOT EXISTS (SELECT 1 FROM pg_depend d
                  WHERE d.classid = 'pg_opclass'::regclass AND d.objid = oc.oid AND d.deptype = 'e')
UNION ALL
SELECT format('ALTER CONVERSION %I.%I OWNER TO %I', n.nspname, cv.conname, :'app_user')
FROM pg_conversion cv JOIN pg_namespace n ON n.oid = cv.connamespace
WHERE n.nspname = 'public' AND cv.conowner = :admin_oid
  AND NOT EXISTS (SELECT 1 FROM pg_depend d
                  WHERE d.classid = 'pg_conversion'::regclass AND d.objid = cv.oid AND d.deptype = 'e')
UNION ALL
SELECT format('ALTER TEXT SEARCH DICTIONARY %I.%I OWNER TO %I', n.nspname, td.dictname, :'app_user')
FROM pg_ts_dict td JOIN pg_namespace n ON n.oid = td.dictnamespace
WHERE n.nspname = 'public' AND td.dictowner = :admin_oid
  AND NOT EXISTS (SELECT 1 FROM pg_depend d
                  WHERE d.classid = 'pg_ts_dict'::regclass AND d.objid = td.oid AND d.deptype = 'e')
UNION ALL
SELECT format('ALTER TEXT SEARCH CONFIGURATION %I.%I OWNER TO %I', n.nspname, tc.cfgname, :'app_user')
FROM pg_ts_config tc JOIN pg_namespace n ON n.oid = tc.cfgnamespace
WHERE n.nspname = 'public' AND tc.cfgowner = :admin_oid
  AND NOT EXISTS (SELECT 1 FROM pg_depend d
                  WHERE d.classid = 'pg_ts_config'::regclass AND d.objid = tc.oid AND d.deptype = 'e')
UNION ALL
SELECT format('ALTER STATISTICS %I.%I OWNER TO %I', n.nspname, st.stxname, :'app_user')
FROM pg_statistic_ext st JOIN pg_namespace n ON n.oid = st.stxnamespace
WHERE n.nspname = 'public' AND st.stxowner = :admin_oid
UNION ALL
SELECT format('ALTER LARGE OBJECT %s OWNER TO %I', lo.oid, :'app_user')
FROM pg_largeobject_metadata lo
WHERE lo.lomowner = :admin_oid
\gexec

-- Completeness check: nothing in public may still belong to the admin, other
-- than extension members and the objects that follow an owner (see above).
-- It covers every catalog with an owner column for schema objects, so it also
-- catches a kind this script does not move (or a bug in the list above):
-- the whole migration is then rolled back.
SELECT count(*) > 0 AS has_leftovers,
       coalesce(string_agg(obj, ', '), '') AS leftovers
FROM (
    SELECT format('relation %s', c.oid::regclass) AS obj
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relowner = :admin_oid
      AND NOT EXISTS (SELECT 1 FROM pg_depend d
                      WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e')
    UNION ALL
    SELECT format('routine %s', p.oid::regprocedure)
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.proowner = :admin_oid
      AND NOT EXISTS (SELECT 1 FROM pg_depend d
                      WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e')
    UNION ALL
    SELECT format('type %s', t.oid::regtype)
    FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'public' AND t.typowner = :admin_oid
      AND t.typtype <> 'm' AND t.typcategory <> 'A'
      AND NOT (t.typtype = 'c' AND (SELECT relkind FROM pg_class WHERE oid = t.typrelid) <> 'c')
      AND NOT EXISTS (SELECT 1 FROM pg_depend d
                      WHERE d.classid = 'pg_type'::regclass AND d.objid = t.oid AND d.deptype = 'e')
    UNION ALL
    SELECT format('collation %s', co.collname)
    FROM pg_collation co JOIN pg_namespace n ON n.oid = co.collnamespace
    WHERE n.nspname = 'public' AND co.collowner = :admin_oid
      AND NOT EXISTS (SELECT 1 FROM pg_depend d
                      WHERE d.classid = 'pg_collation'::regclass AND d.objid = co.oid AND d.deptype = 'e')
    UNION ALL
    SELECT format('operator %s', o.oid::regoperator)
    FROM pg_operator o JOIN pg_namespace n ON n.oid = o.oprnamespace
    WHERE n.nspname = 'public' AND o.oprowner = :admin_oid
      AND NOT EXISTS (SELECT 1 FROM pg_depend d
                      WHERE d.classid = 'pg_operator'::regclass AND d.objid = o.oid AND d.deptype = 'e')
    UNION ALL
    SELECT format('operator family %s', f.opfname)
    FROM pg_opfamily f JOIN pg_namespace n ON n.oid = f.opfnamespace
    WHERE n.nspname = 'public' AND f.opfowner = :admin_oid
      AND NOT EXISTS (SELECT 1 FROM pg_depend d
                      WHERE d.classid = 'pg_opfamily'::regclass AND d.objid = f.oid AND d.deptype = 'e')
    UNION ALL
    SELECT format('operator class %s', oc.opcname)
    FROM pg_opclass oc JOIN pg_namespace n ON n.oid = oc.opcnamespace
    WHERE n.nspname = 'public' AND oc.opcowner = :admin_oid
      AND NOT EXISTS (SELECT 1 FROM pg_depend d
                      WHERE d.classid = 'pg_opclass'::regclass AND d.objid = oc.oid AND d.deptype = 'e')
    UNION ALL
    SELECT format('conversion %s', cv.conname)
    FROM pg_conversion cv JOIN pg_namespace n ON n.oid = cv.connamespace
    WHERE n.nspname = 'public' AND cv.conowner = :admin_oid
      AND NOT EXISTS (SELECT 1 FROM pg_depend d
                      WHERE d.classid = 'pg_conversion'::regclass AND d.objid = cv.oid AND d.deptype = 'e')
    UNION ALL
    SELECT format('text search dictionary %s', td.dictname)
    FROM pg_ts_dict td JOIN pg_namespace n ON n.oid = td.dictnamespace
    WHERE n.nspname = 'public' AND td.dictowner = :admin_oid
      AND NOT EXISTS (SELECT 1 FROM pg_depend d
                      WHERE d.classid = 'pg_ts_dict'::regclass AND d.objid = td.oid AND d.deptype = 'e')
    UNION ALL
    SELECT format('text search configuration %s', tc.cfgname)
    FROM pg_ts_config tc JOIN pg_namespace n ON n.oid = tc.cfgnamespace
    WHERE n.nspname = 'public' AND tc.cfgowner = :admin_oid
      AND NOT EXISTS (SELECT 1 FROM pg_depend d
                      WHERE d.classid = 'pg_ts_config'::regclass AND d.objid = tc.oid AND d.deptype = 'e')
    UNION ALL
    SELECT format('statistics object %s', st.stxname)
    FROM pg_statistic_ext st JOIN pg_namespace n ON n.oid = st.stxnamespace
    WHERE n.nspname = 'public' AND st.stxowner = :admin_oid
    UNION ALL
    SELECT format('large object %s', lo.oid)
    FROM pg_largeobject_metadata lo
    WHERE lo.lomowner = :admin_oid
    UNION ALL
    -- Only public is migrated: the services use no other schema. Any other
    -- user schema needs a decision by hand, so it stops the migration.
    SELECT format('schema %I (only public is migrated; move or drop it by hand)', nspname)
    FROM pg_namespace
    WHERE nspname NOT IN ('public', 'information_schema') AND nspname NOT LIKE 'pg\_%'
) leftover
\gset

\if :has_leftovers
    \echo 'Not migrated (still owned by the admin, or outside public):' :leftovers
    ROLLBACK;
    -- psql's \quit takes no exit code; an error under ON_ERROR_STOP exits with 3.
    DO $$ BEGIN RAISE EXCEPTION 'Migration aborted, nothing was changed (see the objects listed above)'; END $$;
\endif

COMMIT;
\echo 'Done: the services'' role is no longer a superuser and owns everything in public.'
