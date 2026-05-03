CREATE TABLE shedlock
(
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);

COMMENT ON TABLE shedlock IS 'Distributed scheduler lock — prevents duplicate execution across replicas';
COMMENT ON COLUMN shedlock.name IS 'Unique lock name matching @SchedulerLock name parameter';
COMMENT ON COLUMN shedlock.lock_until IS 'Lock expiry timestamp — auto-released if pod crashes';
COMMENT ON COLUMN shedlock.locked_at IS 'Timestamp when lock was acquired';
COMMENT ON COLUMN shedlock.locked_by IS 'Hostname of pod holding the lock';