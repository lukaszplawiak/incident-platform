-- Replaces two separate single-column indexes on incident_history with one
-- composite index that covers the actual query pattern:
--
--   findByIncidentIdAndTenantIdOrderByChangedAtAsc:
--   WHERE incident_id = ? AND tenant_id = ? ORDER BY changed_at ASC
--
-- With the previous two single-column indexes, PostgreSQL had to choose
-- between them (or do a bitmap index scan merging both), then filter the
-- other column in memory, then sort by changed_at separately. The new
-- composite index lets PostgreSQL satisfy all three parts of the query —
-- both equality filters AND the sort — in a single index scan with no
-- additional sort step (the index already stores rows in changed_at ASC
-- order within each (incident_id, tenant_id) group).
--
-- Column ordering rationale:
--   1. incident_id first: highest selectivity (UUID → effectively unique per
--      incident), narrowing the result set most aggressively before applying
--      the tenant filter.
--   2. tenant_id second: further narrows within a single incident's rows.
--      In a correctly operating system this should always return 1 row per
--      incident per tenant, making this filter near-trivial in practice,
--      but it's still required for multi-tenant correctness.
--   3. changed_at ASC last: matches ORDER BY direction, eliminating the
--      sort step. Stored ASC because history is always read chronologically.
--
-- idx_incident_history_changed_at (single-column on changed_at DESC) is also
-- dropped — it was only useful for cross-tenant queries sorted by time, which
-- don't exist in the codebase. The composite index covers the only real
-- query pattern.
--
-- In production with a large table, prefer CREATE INDEX CONCURRENTLY (outside
-- a Flyway transaction) to avoid locking. For this dev/portfolio environment,
-- a standard CREATE INDEX is used.

DROP INDEX IF EXISTS idx_incident_history_incident_id;
DROP INDEX IF EXISTS idx_incident_history_tenant_id;
DROP INDEX IF EXISTS idx_incident_history_changed_at;

CREATE INDEX idx_incident_history_incident_tenant_at
    ON incident_history (incident_id, tenant_id, changed_at ASC);