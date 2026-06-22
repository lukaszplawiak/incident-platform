-- Adds a composite index for the Slack user lookup query added in the
-- fix/slack-user-lookup-tenant-isolation branch:
--
--   findByTenantIdAndSlackUserId:
--   WHERE tenant_id = ? AND slack_user_id = ? ORDER BY starts_at DESC
--
-- Without this index, every Slack ACK button click triggers a full table
-- scan on oncall_schedules — the query has no index to use since neither
-- column was previously indexed together. slack_user_id has no index at all
-- in V1, meaning PostgreSQL must scan the entire table and filter in memory.
--
-- The existing idx_oncall_schedules_tenant_time (tenant_id, starts_at, ends_at)
-- is NOT a substitute here — it would let PostgreSQL find all schedules for a
-- tenant ordered by time, but can't satisfy the slack_user_id equality filter
-- efficiently without scanning all of them.
--
-- Column ordering: tenant_id first (multi-tenant isolation filter, always
-- present), slack_user_id second (equality filter — high selectivity since
-- Slack user IDs are unique per workspace). starts_at DESC last to support
-- ORDER BY without an additional sort step — the most recent schedule for
-- this Slack user within the tenant is returned first.
--
-- The existing single-column indexes (tenant_time, user) are left in place —
-- they cover different queries (findCurrentOncallByRole, findAllCurrentOncall,
-- findByTenantIdOrderByStartsAtDesc) that don't filter by slack_user_id.

CREATE INDEX idx_oncall_schedules_tenant_slack
    ON oncall_schedules (tenant_id, slack_user_id, starts_at DESC);