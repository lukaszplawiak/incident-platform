-- Backlog #0-18 / #0-19.
--
-- 1. A queue entry can now end as UNDELIVERABLE: nobody in the tenant could be
--    notified (nobody on call, no reachable channel, or oncall-service
--    unavailable past the retry window). Its incident text is deliberately not
--    sent to any platform-wide destination; the operator is told with a
--    content-free alert instead. The reason is stored in error_message.
--    The CHECK constraint from V3 allows only PENDING, SENT and FAILED, so it is
--    replaced by one that also allows UNDELIVERABLE.
--
-- 2. first_lookup_failure_at: when oncall-service first failed to answer the
--    lookup for this entry. The retry window (notification.scheduler.
--    lookup-retry-window) is measured from it and not from created_at: after a
--    restart or an outage longer than the window, an entry is already older than
--    created_at + window on its first attempt, and one transient failure would
--    otherwise park a whole backlog as UNDELIVERABLE at once.
--
-- Rolling deploy: the schema change is safe. Existing rows and the code that runs
-- before this migration write only the three old statuses, which the new
-- constraint still accepts, and the new column is nullable. The BEHAVIOUR change
-- (no platform-wide fallback address) only applies once no old pod is left: an
-- old pod's scheduler can still process a PENDING entry the old way until then.
-- Rolling the application back after UNDELIVERABLE rows exist is safe for the old
-- code (the query that loads entries filters on PENDING, and its idempotency checks
-- only return a boolean, so neither deserialises the new status), but re-adding the
-- old constraint by hand would fail on those rows.
--
-- Locking: DROP/ADD CONSTRAINT and ADD COLUMN take ACCESS EXCLUSIVE on
-- notification_queue, and ADD CONSTRAINT scans the existing rows under that lock.
-- The table is a small outbox, so a plain transactional migration is used, like V5
-- (see its header). SET LOCAL lock_timeout only bounds how long a statement WAITS
-- for its lock, so the migration fails fast and rolls back cleanly instead of
-- queueing behind a long-running transaction and stalling every query behind it.

SET LOCAL lock_timeout = '5s';

ALTER TABLE notification_queue
    DROP CONSTRAINT chk_notification_queue_status;

ALTER TABLE notification_queue
    ADD CONSTRAINT chk_notification_queue_status
        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'UNDELIVERABLE'));

ALTER TABLE notification_queue
    ADD COLUMN first_lookup_failure_at TIMESTAMPTZ;

COMMENT ON COLUMN notification_queue.status
    IS 'PENDING=awaiting processing, SENT=all channels processed, '
       'FAILED=processing failed, UNDELIVERABLE=nobody in the tenant could be notified '
       '(reason in error_message)';

COMMENT ON COLUMN notification_queue.processed_at
    IS 'Timestamp when the scheduler reached a terminal state for this entry '
       '(SENT, FAILED or UNDELIVERABLE).';

COMMENT ON COLUMN notification_queue.first_lookup_failure_at
    IS 'When oncall-service first failed to answer the recipient lookup for this entry; '
       'the retry window is measured from it. NULL if no lookup has failed.';
