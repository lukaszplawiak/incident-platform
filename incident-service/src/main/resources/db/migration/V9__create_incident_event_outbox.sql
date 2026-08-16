-- Outbox table for incident-service's Kafka publishing (backlog #36).
--
-- Fixed: IncidentEventPublisher previously called IncidentEventKafkaSender
-- directly from within IncidentCommandService's @Transactional methods —
-- publishing to Kafka (async, fire-and-forget) BEFORE the enclosing
-- database transaction actually committed. If anything later in the same
-- transactional method caused a rollback (a DB constraint violation
-- surfacing only at flush/commit time, an unexpected exception in a
-- subsequent step), the Kafka event had already been irreversibly sent —
-- external consumers (escalation-service, notification-service,
-- postmortem-service) would receive and act on a "phantom" event
-- describing an incident change that, in the database, never actually
-- happened.
--
-- This table is the fix: IncidentEventPublisher now writes a PENDING
-- outbox row in the SAME transaction as the Incident/IncidentHistory
-- change, instead of calling Kafka directly. IncidentEventOutboxScheduler
-- polls PENDING rows on a short interval and publishes them to Kafka only
-- after they're durably committed — by construction, an outbox row only
-- exists if its transaction actually committed, so there is no phantom-
-- event scenario anymore. Same structural pattern as auth-service's
-- auth_email_outbox and notification-service's notification_queue,
-- adapted for a Kafka-publish payload instead of an email/notification
-- send.
--
-- Only two states (PENDING/PUBLISHED), unlike auth_email_outbox's four
-- (PENDING/SENT/FAILED/PERMANENTLY_FAILED): a Kafka publish failure is
-- virtually always a transient broker-reachability issue, and every
-- consumer of incidents.lifecycle already handles at-least-once,
-- idempotent processing (fingerprint-based dedup in incident-service
-- itself, idempotent FSM transitions) — there is no scenario where
-- giving up on delivering a real incident lifecycle event is the right
-- call, unlike an invalid email address that will never succeed no
-- matter how many times it's retried.
CREATE TABLE incident_event_outbox
(
    id            UUID         NOT NULL,
    incident_id   UUID         NOT NULL,
    tenant_id     VARCHAR(255) NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    payload       TEXT         NOT NULL,

    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
        CONSTRAINT chk_incident_event_outbox_status
            CHECK (status IN ('PENDING', 'PUBLISHED')),

    retry_count   INT          NOT NULL DEFAULT 0,
    error_message TEXT,

    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at  TIMESTAMPTZ,

    CONSTRAINT pk_incident_event_outbox PRIMARY KEY (id)
);

-- Fast lookup for the scheduler: all PENDING entries, oldest first.
CREATE INDEX idx_incident_event_outbox_status_created
    ON incident_event_outbox (status, created_at)
    WHERE status = 'PENDING';

-- Secondary lookup for debugging/support: all outbox history for one incident.
CREATE INDEX idx_incident_event_outbox_incident_id
    ON incident_event_outbox (incident_id);

COMMENT ON TABLE incident_event_outbox
    IS 'Outbox table (backlog #36) — IncidentEventPublisher writes PENDING '
       'entries here in the same transaction as the business data change. '
       'IncidentEventOutboxScheduler publishes them to Kafka afterward, on '
       'a dedicated scheduled thread, only once durably committed.';

COMMENT ON COLUMN incident_event_outbox.status
    IS 'PENDING=awaiting publish, PUBLISHED=confirmed delivered to Kafka. '
       'No permanent-failure state — see table comment for why every '
       'failure is treated as retryable.';

-- ShedLock table — prevents duplicate scheduler execution across multiple
-- incident-service instances. Did not exist before this migration:
-- incident-service had no scheduled jobs of any kind prior to backlog #36.
CREATE TABLE IF NOT EXISTS shedlock
(
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
    );