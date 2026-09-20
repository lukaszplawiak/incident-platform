-- Escalation-level-aware, tenant-scoped idempotency for notifications, and
-- carrying the escalation target through the outbox.
--
-- Problem: level 1 (SECONDARY) and level 2 (MANAGER) of one escalation are
-- both INCIDENT_ESCALATED events for the same incident. The idempotency key
-- was (incident_id, event_type) on notification_queue and
-- (incident_id, event_type, channel) on notification_log, so once the level-1
-- escalation was queued/sent, the level-2 escalation was discarded as a
-- "duplicate" and never delivered.
--
-- Fix: the escalation level becomes part of the key. Rows for every other
-- event type keep escalation_level = 0, so their behaviour is unchanged.
-- tenant_id is added to the same keys so that idempotency, like every other
-- query in this service, is tenant-scoped: without it, an event forged under
-- another tenant with a victim's incident_id could pre-empt the victim's
-- real notification.
--
-- escalate_to holds IncidentEscalatedEvent.escalateTo (the user the
-- escalation is addressed to). It is stored here because the recipient is
-- resolved at send time by the scheduler, not by the Kafka consumer. NULL for
-- non-escalation events and for escalations published without a target.
--
-- Rollout window (old and new pods running side by side):
--   * Nothing crashes: both new columns have a default or are nullable, so
--     pods still on the previous version keep inserting valid rows.
--   * Old pods know nothing about the level: they write level 0 for every
--     event, escalations included, and still discard level 2 as a duplicate.
--   * Old schedulers share the queue: a level-2 entry enqueued by a NEW pod
--     and picked up by an OLD pod's scheduler is skipped by the old
--     level-blind per-channel check (level 1 already logged that channel)
--     and then marked SENT without anything being sent; nothing retries it.
--     The loss is bounded to the rollout window (it is the bug this
--     migration fixes, seen from the old side), so keep the overlap short.
--   * Duplicate risk: an escalation queued by an old pod at level 0 and
--     redelivered by Kafka to a new pod (rebalance) is queued again at its
--     real level and can be sent twice. Escalation rows written before this
--     migration are also stored at level 0 and cannot be corrected, so a
--     replay of an old escalation event after deploy is not deduplicated.
--
-- Locking: ALTER TABLE ... ADD COLUMN takes ACCESS EXCLUSIVE (blocks reads
-- and writes), the CHECK constraints scan the existing rows under that lock,
-- and CREATE INDEX takes SHARE (blocks writes) for the duration of the build.
-- Both tables are small outbox/audit tables, so a plain transactional
-- migration is used. SET LOCAL lock_timeout only bounds how long a statement
-- WAITS for its lock (so the migration fails fast, and cleanly rolls back,
-- instead of queueing behind a long-running transaction and stalling every
-- query behind it); it does not bound how long a statement runs. It only
-- works because this file is transactional: keep it that way. If
-- notification_log ever grows large, add the CHECK as NOT VALID and VALIDATE
-- it afterwards, and build the new index with CREATE INDEX CONCURRENTLY in a
-- separate non-transactional migration.

SET LOCAL lock_timeout = '5s';

ALTER TABLE notification_queue
    ADD COLUMN escalation_level INT  NOT NULL DEFAULT 0
        CONSTRAINT chk_notification_queue_escalation_level
            CHECK (escalation_level >= 0),
    ADD COLUMN escalate_to      UUID;

ALTER TABLE notification_log
    ADD COLUMN escalation_level INT NOT NULL DEFAULT 0
        CONSTRAINT chk_notification_log_escalation_level
            CHECK (escalation_level >= 0);

COMMENT ON COLUMN notification_queue.escalation_level
    IS 'Escalation level of an IncidentEscalatedEvent (1 = SECONDARY, 2 = MANAGER); '
       '0 for every other event type. Part of the idempotency key.';
COMMENT ON COLUMN notification_queue.escalate_to
    IS 'User the escalation is addressed to (IncidentEscalatedEvent.escalateTo); '
       'NULL when absent. Stored because the recipient is resolved at send time.';
COMMENT ON COLUMN notification_log.escalation_level
    IS 'Escalation level of the event this send belongs to; 0 for non-escalation events.';

-- notification_queue: idempotency key now includes tenant and escalation level.
DROP INDEX uq_notification_queue_incident_event;

CREATE UNIQUE INDEX uq_notification_queue_incident_tenant_event_level
    ON notification_queue (incident_id, tenant_id, event_type, escalation_level);

-- notification_log: covers the per-channel idempotency check
-- existsByIncidentIdAndTenantIdAndEventTypeAndEscalationLevelAndChannel
-- (replaces idx_notification_log_incident_type_channel from V2).
DROP INDEX idx_notification_log_incident_type_channel;

CREATE INDEX idx_notification_log_incident_tenant_type_level_channel
    ON notification_log (incident_id, tenant_id, event_type, escalation_level, channel);
