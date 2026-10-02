-- The audit event's own id as the idempotency key (backlog #0-84).
--
-- Producers now write audit events to an outbox and a relay sends them to
-- Kafka at least once: after a crash between Kafka's acknowledgement and the
-- relay marking the row sent, the same event is sent again as a NEW record,
-- with another (partition, offset). V10's key catches only a redelivery of one
-- record, so the event carries an id (AuditEventMessage.eventId) and V13's
-- unique index turns a resend into a duplicate the consumer skips.
--
-- Nullable: rows from before, and records from producers not yet on the
-- outbox, have no event id; Postgres treats NULLs as distinct, so they never
-- conflict. V10's index stays for those.
--
-- Rolling deploy: an older consumer ignores the new JSON field (unknown
-- properties are not fatal) and writes NULL here, so a resend reaching it
-- before it is replaced can still be stored twice; the window closes with the
-- deploy.

ALTER TABLE audit_events
    ADD COLUMN event_id UUID;

-- The unique index is V13's, built CONCURRENTLY outside a transaction (found
-- in review: audit_events is the platform's largest table, and a plain build
-- would block the audit consumer's inserts for the whole scan).

COMMENT ON COLUMN audit_events.event_id
    IS 'The event''s own id from its producer (backlog #0-84): idempotency key against '
       'outbox resends, which arrive as new Kafka records. NULL before the outbox.';
