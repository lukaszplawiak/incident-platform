-- Adds optimistic locking version column to users table.
--
-- @Version in JPA/Hibernate automatically increments this counter on every
-- UPDATE. If two concurrent requests read the same version and both attempt
-- to save, the second write throws OptimisticLockException — translated by
-- Spring to OptimisticLockingFailureException and handled by
-- GlobalExceptionHandler as 409 Conflict.
--
-- DEFAULT 0 ensures all existing rows get version=0 without a data migration.
-- Hibernate will start incrementing from 0 on the next UPDATE to each row.
--
-- Why Long instead of Integer: Long avoids overflow on very long-lived records
-- (2^63 vs 2^31 increments before overflow). Negligible storage difference.

ALTER TABLE users
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN users.version
    IS 'Optimistic locking counter — incremented by Hibernate on every UPDATE. '
       'Prevents lost updates when concurrent requests modify the same user.';