-- Unique (tenant_id, event_id) on audit_events (backlog #0-84; column: V12).
--
-- An outbox relay sends at least once, so the same event can arrive again as
-- a new Kafka record; this index turns the second insert into a duplicate
-- AuditEventConsumer acknowledges. Per tenant (found in review): an event id
-- is a random UUID, so a collision across tenants never happens by accident,
-- and anyone able to write to audit.events must not be able to suppress
-- another tenant's event by sending its id first under their own tenant.
-- Partial: rows from before the outbox, and producers not on it yet, have no
-- event id; they keep V10's (partition, offset) key.
--
-- CONCURRENTLY (found in review): audit_events grows with every audit event
-- of every service, and a plain CREATE INDEX holds a lock that blocks the
-- audit consumer's inserts for the whole build. Flyway runs a migration whose
-- statements cannot run in a transaction outside one, so this file holds
-- nothing else. A build that fails leaves an INVALID index behind; the DROP
-- makes a rerun (after flyway repair) start clean. The earlier migrations of
-- this kind (V6, notification-service V2) noted CONCURRENTLY as the
-- production choice and took the plain build for a small table.

DROP INDEX CONCURRENTLY IF EXISTS uq_audit_events_tenant_event_id;

CREATE UNIQUE INDEX CONCURRENTLY uq_audit_events_tenant_event_id
    ON audit_events (tenant_id, event_id)
    WHERE event_id IS NOT NULL;
