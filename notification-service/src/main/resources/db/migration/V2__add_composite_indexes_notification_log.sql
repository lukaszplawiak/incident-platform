-- Replaces two separate single-column indexes on notification_log with
-- composite indexes that cover the two actual query patterns:
--
-- Query 1 — findByIncidentIdAndTenantIdOrderBySentAtDesc:
--   WHERE incident_id = ? AND tenant_id = ? ORDER BY sent_at DESC
--
-- Query 2 — existsByIncidentIdAndEventTypeAndChannel:
--   WHERE incident_id = ? AND event_type = ? AND channel = ?
--
-- The single-column indexes (idx_notification_log_incident_id,
-- idx_notification_log_tenant_id) forced PostgreSQL to pick one, filter
-- the other in memory, then sort separately. idx_notification_log_sent_at
-- (single-column DESC) was only useful if queries sorted by sent_at without
-- filtering by incident — no such query exists.
--
-- New composite indexes:
--
-- idx_notification_log_incident_tenant_sent: covers Query 1 entirely.
--   Column order: incident_id (highest selectivity — UUID) → tenant_id →
--   sent_at DESC (matches ORDER BY direction, eliminating the sort step).
--
-- idx_notification_log_incident_type_channel: covers Query 2 entirely.
--   Column order: incident_id (highest selectivity) → event_type → channel.
--   Used by NotificationService.existsByIncidentIdAndEventTypeAndChannel()
--   to deduplicate notifications (prevent sending the same EMAIL/SLACK/SMS
--   twice for the same event). This is a hot path — called before every
--   notification send.
--
-- In production with a large table, prefer CREATE INDEX CONCURRENTLY (outside
-- a Flyway transaction) to avoid locking. For this dev/portfolio environment,
-- a standard CREATE INDEX is used.

DROP INDEX IF EXISTS idx_notification_log_incident_id;
DROP INDEX IF EXISTS idx_notification_log_tenant_id;
DROP INDEX IF EXISTS idx_notification_log_sent_at;

CREATE INDEX idx_notification_log_incident_tenant_sent
    ON notification_log (incident_id, tenant_id, sent_at DESC);

CREATE INDEX idx_notification_log_incident_type_channel
    ON notification_log (incident_id, event_type, channel);