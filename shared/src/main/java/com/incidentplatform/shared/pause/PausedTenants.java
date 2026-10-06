package com.incidentplatform.shared.pause;

import com.incidentplatform.shared.security.TenantAccess;
import com.incidentplatform.shared.security.TenantIds;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A service's table of suspended tenants whose background work it holds back
 * (backlog #0-82, step 2b), through plain JDBC so one class in {@code shared}
 * serves every service's own table, as {@code AuditOutbox} does.
 *
 * <h2>Why a table, not a check in Java</h2>
 * The schedulers that act for a tenant (notification-, escalation- and
 * postmortem-service) pick their work oldest first, a batch at a time. A
 * suspended tenant's rows are the oldest ones; skipped in Java they would fill
 * every batch and starve every other tenant. Each scheduler's query excludes
 * this table's tenants instead ({@code NOT EXISTS}). A table, not a list held in
 * memory, because the pause must outlive a restart and be the same on every
 * replica (ShedLock moves the scheduler between them), and because
 * escalation-service needs to know when the pause began to stop its timers for
 * its length.
 *
 * <p>One writer: {@link PausedTenantsSync}, under its ShedLock lock. The
 * schedulers only read it.
 */
public class PausedTenants {

    /** One paused tenant: what auth-service last said it may do, and since when (its suspension). */
    public record Paused(String tenantId, TenantAccess access, Instant pausedAt) {
    }

    /** What {@link #pause} changed. */
    public enum Change {
        /** The tenant was not paused, and now is. */
        PAUSED,
        /** It was paused already, in the other mode; {@code paused_at} is kept. */
        MODE_CHANGED,
        /** It was paused already, in this mode. */
        UNCHANGED
    }

    /**
     * The longest table name: the sync's ShedLock lock is named after it with a
     * prefix, and {@code shedlock.name} is {@code VARCHAR(64)} in every service.
     */
    static final int MAX_TABLE_NAME_LENGTH = 64 - PausedTenantsSync.LOCK_PREFIX.length();

    /** The table name is interpolated into SQL, so it is checked, not trusted. */
    private static final Pattern TABLE_NAME =
            Pattern.compile("[a-z][a-z0-9_]{0," + (MAX_TABLE_NAME_LENGTH - 1) + "}");

    private final JdbcTemplate jdbc;
    private final String table;

    /**
     * @throws IllegalArgumentException when {@code table} is not a plain
     *         lower-case name of at most {@link #MAX_TABLE_NAME_LENGTH} characters
     */
    public PausedTenants(JdbcTemplate jdbc, String table) {
        this.jdbc = jdbc;
        this.table = checkedTableName(table);
    }

    static String checkedTableName(String table) {
        if (table == null || !TABLE_NAME.matcher(table).matches()) {
            throw new IllegalArgumentException("tenant-pause.table must be a plain lower-case table name of at most "
                    + MAX_TABLE_NAME_LENGTH + " characters, was " + table);
        }
        return table;
    }

    public String table() {
        return table;
    }

    /** Every paused tenant, longest paused first. */
    public List<Paused> all() {
        return jdbc.query("SELECT tenant_id, access, paused_at FROM " + table + " ORDER BY paused_at, tenant_id",
                (rs, n) -> new Paused(rs.getString("tenant_id"), TenantAccess.valueOf(rs.getString("access")),
                        rs.getTimestamp("paused_at").toInstant()));
    }

    public long count() {
        final Long count = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count != null ? count : 0;
    }

    /**
     * Pauses the tenant from when it was suspended, or records a change of mode
     * while keeping when the pause began: a READ_ONLY suspension turned FULL is
     * one pause, not two (auth-service keeps {@code suspended_at} across it
     * too).
     *
     * @param access what auth-service says the tenant may do; never FULL
     * @param since  when the suspension began, as auth-service said
     *               ({@code suspended_at}, the same database's clock); {@code
     *               null} when it did not say (an older auth-service), and the
     *               pause then begins now, when this service saw it
     */
    public Change pause(String tenantId, TenantAccess access, Instant since) {
        TenantIds.requireValid(tenantId);
        if (access == null || access == TenantAccess.FULL) {
            throw new IllegalArgumentException("A tenant with full access is not paused: " + access);
        }
        // xmax = 0 only on a row this statement inserted; an empty result means
        // the WHERE of DO UPDATE kept the row as it was (same mode).
        final List<Boolean> inserted = jdbc.query("INSERT INTO " + table + " AS p (tenant_id, access, paused_at)"
                        + " VALUES (?, ?, COALESCE(CAST(? AS timestamptz), now()))"
                        + " ON CONFLICT (tenant_id) DO UPDATE SET access = EXCLUDED.access"
                        + " WHERE p.access <> EXCLUDED.access"
                        + " RETURNING (xmax = 0) AS inserted",
                (rs, n) -> rs.getBoolean("inserted"), tenantId, access.name(),
                since == null ? null : Timestamp.from(since));
        if (inserted.isEmpty()) {
            return Change.UNCHANGED;
        }
        return inserted.get(0) ? Change.PAUSED : Change.MODE_CHANGED;
    }

    /**
     * Locks the tenant's row for the transaction that ends its pause and says
     * when the pause began; empty when it is not paused (any more). Must run in
     * that transaction.
     */
    public Optional<Instant> lockForResume(String tenantId) {
        final List<Timestamp> pausedAt = jdbc.queryForList("SELECT paused_at FROM " + table
                + " WHERE tenant_id = ? FOR UPDATE", Timestamp.class, tenantId);
        return pausedAt.isEmpty() ? Optional.empty() : Optional.of(pausedAt.get(0).toInstant());
    }

    /** Ends the tenant's pause; {@code true} if it was paused. */
    public boolean delete(String tenantId) {
        return jdbc.update("DELETE FROM " + table + " WHERE tenant_id = ?", tenantId) == 1;
    }
}
