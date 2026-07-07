-- Outbox table for notification-service.
--
-- Design: two separate tables for two separate concerns:
--
--   notification_queue  — mutable outbox / work queue.
--                         Kafka consumer writes PENDING entries here and
--                         acknowledges immediately. NotificationScheduler
--                         reads PENDING, sends the actual notification,
--                         then marks SENT or FAILED. This table is the
--                         source of truth for "what needs to be sent".
--
--   notification_log    — immutable audit trail (unchanged).
--                         Written only after a notification is successfully
--                         sent or permanently fails. Never mutated.
--
-- Keeping them separate preserves notification_log as an append-only audit
-- trail while allowing notification_queue to be a mutable work queue.
--
-- Why store eventType / severity / title in the queue and NOT recipient:
--   The recipient (oncall person) is resolved at send time by the scheduler,
--   not at enqueue time by the consumer. This is intentional — the oncall
--   schedule may rotate in the 30 seconds between enqueue and send, and we
--   always want to notify whoever is currently on duty, not whoever was on
--   duty when the Kafka event arrived.

CREATE TABLE notification_queue
(
    id           UUID         NOT NULL,
    incident_id  UUID         NOT NULL,
    tenant_id    VARCHAR(255) NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    severity     VARCHAR(50)  NOT NULL,
    title        VARCHAR(500) NOT NULL,

    -- Mutable outbox state
    status       VARCHAR(50)  NOT NULL DEFAULT 'PENDING'
        CONSTRAINT chk_notification_queue_status
            CHECK (status IN ('PENDING', 'SENT', 'FAILED')),

    error_message TEXT,

    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMPTZ,

    CONSTRAINT pk_notification_queue PRIMARY KEY (id)
);

-- Fast lookup for scheduler: all PENDING entries older than the threshold
CREATE INDEX idx_notification_queue_status_created
    ON notification_queue (status, created_at)
    WHERE status = 'PENDING';

-- Idempotency: prevent duplicate queue entries for the same event
CREATE UNIQUE INDEX uq_notification_queue_incident_event
    ON notification_queue (incident_id, event_type);

COMMENT ON TABLE notification_queue
    IS 'Outbox table — Kafka consumer writes PENDING entries here and acknowledges '
       'immediately. NotificationScheduler processes PENDING entries asynchronously.';

COMMENT ON COLUMN notification_queue.status
    IS 'PENDING=awaiting processing, SENT=all channels sent, FAILED=processing failed';

COMMENT ON COLUMN notification_queue.processed_at
    IS 'Timestamp when the scheduler processed this entry (SENT or FAILED).';

-- ShedLock table — prevents duplicate scheduler execution across
-- multiple notification-service instances.
CREATE TABLE shedlock
(
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);