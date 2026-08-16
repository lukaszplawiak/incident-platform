-- Idempotency key for audit_events, sourced from Kafka's own (partition,
-- offset) coordinate — every message on a topic has a permanently unique
-- one, with zero extra work needed from the producer side.
--
-- Fixed: AuditEventConsumer previously called auditEventRepository.save(...)
-- with a fresh, randomly-generated UUID primary key on every call — meaning
-- if the consumer crashed after a successful save but before acknowledging
-- the Kafka offset, redelivery of the same message would insert a second,
-- duplicate row with no way for the database to recognize it as a repeat.
-- Kafka guarantees at-least-once delivery, not exactly-once, for external
-- side effects like a database write — this is the standard, production-
-- proven fix for that gap: derive a deterministic idempotency key from the
-- message's own Kafka coordinates, enforce uniqueness on it at the database
-- level, and treat a uniqueness violation on redelivery as "already
-- processed" rather than an error. Same underlying pattern (a database
-- uniqueness constraint doing the deduplication work, application code
-- treating the resulting DataIntegrityViolationException as an expected,
-- non-error outcome) already used in oncall-service's
-- excl_oncall_schedule_overlap constraint.
--
-- Scoped to (partition, offset) only, not (topic, partition, offset):
-- AuditEventConsumer only ever listens to one topic (audit.events), so
-- topic would be a constant that adds no actual disambiguation value.
ALTER TABLE audit_events
    ADD COLUMN kafka_partition INT,
    ADD COLUMN kafka_offset    BIGINT;

-- Nullable at the column level (existing rows before this migration have
-- no Kafka coordinate to backfill), but the unique constraint below still
-- prevents any future duplicate for a given (partition, offset) pair —
-- Postgres treats NULL as distinct from any other NULL for uniqueness
-- purposes, so pre-existing NULL rows never conflict with each other or
-- with new, correctly-populated rows.
CREATE UNIQUE INDEX uq_audit_events_kafka_partition_offset
    ON audit_events (kafka_partition, kafka_offset);

COMMENT ON COLUMN audit_events.kafka_partition
    IS 'Kafka partition this event was consumed from — part of the '
       'idempotency key preventing duplicate rows on message redelivery.';

COMMENT ON COLUMN audit_events.kafka_offset
    IS 'Kafka offset this event was consumed from — part of the '
       'idempotency key preventing duplicate rows on message redelivery.';