-- Retry tracking for escalation_tasks (backlog #41).
--
-- Fixed: when EscalationScheduler.escalate() fails (e.g. oncall-service
-- unreachable for an extended period) and the task remains PENDING for
-- the next poll cycle to retry, there was previously no record anywhere
-- of how many times this had already been attempted. A log line reading
-- "Failed to escalate task: incidentId=X" looked identical whether this
-- was the first attempt or the five-hundredth — no way to tell a fresh
-- problem from a task stuck retrying for hours from the logs alone.
--
-- Same shape as IncidentEventOutbox's retry_count/error_message
-- (incident-service, backlog #36) and AuthEmailOutbox's equivalent
-- (auth-service) — matched exactly for consistency rather than
-- inventing different column names/semantics for the same concept.
ALTER TABLE escalation_tasks
    ADD COLUMN retry_count   INT NOT NULL DEFAULT 0,
    ADD COLUMN error_message TEXT;

COMMENT ON COLUMN escalation_tasks.retry_count
    IS 'Number of failed escalation attempts (backlog #41) — incremented '
       'only for genuine failures (oncall-service unreachable, DB write '
       'failure), never for a task skipped due to a detected optimistic '
       'lock conflict (backlog #38), since that is an expected, correct '
       'outcome, not a failure.';

COMMENT ON COLUMN escalation_tasks.error_message
    IS 'Most recent failure reason (backlog #41), for diagnostics — '
       'null if the task has never failed an escalation attempt.';