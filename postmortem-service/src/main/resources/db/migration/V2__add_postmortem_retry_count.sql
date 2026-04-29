ALTER TABLE postmortems
    ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE postmortems
DROP CONSTRAINT chk_postmortem_status;

ALTER TABLE postmortems
    ADD CONSTRAINT chk_postmortem_status
        CHECK (status IN ('GENERATING', 'DRAFT', 'FAILED', 'REVIEWED', 'PERMANENTLY_FAILED'));

CREATE INDEX idx_postmortems_status_retry
    ON postmortems (status, retry_count);

COMMENT ON COLUMN postmortems.retry_count IS
    'Number of retry attempts made by PostmortemRetryScheduler';

COMMENT ON COLUMN postmortems.status IS
    'GENERATING=in progress, DRAFT=ready, FAILED=error, REVIEWED=approved, PERMANENTLY_FAILED=retries exhausted';