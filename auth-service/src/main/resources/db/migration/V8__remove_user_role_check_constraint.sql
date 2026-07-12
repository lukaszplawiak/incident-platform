-- Removes the CHECK constraint on user_roles.role.
--
-- Previously: CHECK (role IN ('ROLE_ADMIN', 'ROLE_RESPONDER'))
-- Adding a new role required ALTER TABLE DROP CONSTRAINT + ADD CONSTRAINT —
-- a risky DDL operation on a table with production data.
--
-- After this migration, role validation is enforced at the Java level
-- by the Role enum (@Enumerated(EnumType.STRING) in UserRole.java).
-- Adding a new role = one line in the enum + a data migration.
-- No schema constraint changes needed.
--
-- Existing data is unaffected — all existing rows contain valid enum values.

ALTER TABLE user_roles
DROP CONSTRAINT IF EXISTS chk_user_role;

COMMENT ON COLUMN user_roles.role
    IS 'Role name — validated at application level by Role enum. '
       'Stored as VARCHAR to match Role.name() output (e.g. ROLE_ADMIN).';