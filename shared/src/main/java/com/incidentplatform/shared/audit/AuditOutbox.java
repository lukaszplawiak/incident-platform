package com.incidentplatform.shared.audit;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A service's audit outbox table (backlog #0-84), through plain JDBC so one
 * class in {@code shared} serves every service's own table (a JPA entity
 * would fix the table name at compile time).
 *
 * <h2>Why an outbox</h2>
 * Audit events used to go to Kafka from inside the caller's transaction:
 * an event could describe an action whose commit then failed, a failed
 * delivery was lost without a trace (the send was never awaited), and a
 * Kafka outage held request threads and database connections. Now the event
 * is a row written in the same transaction as the action: both commit or
 * neither does. {@link AuditOutboxRelay} sends committed rows afterwards and
 * marks them sent only once Kafka acknowledged them.
 *
 * <p>JdbcTemplate joins the caller's transaction (Spring's JPA transaction
 * manager exposes its connection to it; {@code AuthRepositoryIntegrationTest}
 * and {@code AuditPersistenceIntegrationTest} check it against the real
 * manager). Without a transaction the insert commits on its own, which is
 * right for an event that records no change, or a refusal written after its
 * transaction rolled back (a wrong MFA code, {@code MfaService}).
 *
 * <p>The payload is kept for {@code audit.outbox.retention} after it is
 * sent, in plain text like every outbox here: audit metadata must never hold
 * a secret (a token, a password, a key), only identifiers.
 *
 * <p>One writer per row after the insert, as for the other outboxes (CLAUDE.md):
 * only the relay updates it, with state-guarded UPDATEs. A service writes
 * through {@link AuditEventStore#enqueue} only; the other methods are the
 * relay's, public so each service's migration test can run them against its
 * own table.
 */
public class AuditOutbox implements AuditEventStore {

    /** A row waiting to be sent. */
    public record Pending(UUID id, String tenantId, String eventType, String payload, int attempts) {
    }

    /**
     * How many rows wait, and how long the oldest has waited, by the
     * database's clock (found in review: the relay's clock against the rows'
     * {@code created_at} mixed two clocks).
     */
    public record Backlog(long pending, Optional<Duration> oldestAge) {
    }

    /** Sent rows deleted per statement, so a purge never holds one huge transaction. */
    static final int PURGE_CHUNK = 5_000;

    private final JdbcTemplate jdbc;
    private final String table;

    public AuditOutbox(JdbcTemplate jdbc, String table) {
        this.jdbc = jdbc;
        this.table = table;
    }

    @Override
    public void enqueue(UUID eventId, String tenantId, String eventType, String payload) {
        jdbc.update("INSERT INTO " + table + " (id, tenant_id, event_type, payload, status, attempts, "
                        + "created_at, next_attempt_at) VALUES (?, ?, ?, ?, 'PENDING', 0, now(), now())",
                eventId, tenantId, eventType, payload);
    }

    /**
     * Rows due for a send attempt, in the order they became due (oldest first
     * for rows never tried: they are written with {@code next_attempt_at =
     * created_at}). Ordered as the partial index {@code idx_..._due} is
     * ({@code next_attempt_at, created_at}), so a long backlog after a Kafka
     * outage is read from the index, not collected and sorted on every run
     * (found in review).
     */
    public List<Pending> due(int limit) {
        return jdbc.query("SELECT id, tenant_id, event_type, payload, attempts FROM " + table
                        + " WHERE status = 'PENDING' AND next_attempt_at <= now()"
                        + " ORDER BY next_attempt_at, created_at LIMIT ?",
                (rs, n) -> new Pending(rs.getObject("id", UUID.class), rs.getString("tenant_id"),
                        rs.getString("event_type"), rs.getString("payload"), rs.getInt("attempts")),
                limit);
    }

    /**
     * Marks a batch's acknowledged rows sent in one statement (found in review:
     * one UPDATE and commit per row made the database the limit once sends were
     * batched).
     *
     * @return how many of them were still pending (one writer: all of them should be)
     */
    public int markSent(List<UUID> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.update(connection -> {
            final PreparedStatement statement = connection.prepareStatement("UPDATE " + table
                    + " SET status = 'SENT', sent_at = now(), attempts = attempts + 1, last_error = NULL"
                    + " WHERE id = ANY (?) AND status = 'PENDING'");
            statement.setArray(1, connection.createArrayOf("uuid", ids.toArray()));
            return statement;
        });
    }

    /**
     * Counts a failed attempt and sets when the row is due again, {@code retryIn}
     * from the database's clock, the clock {@link #due} compares with (found in
     * review: the relay's own clock, skewed against the database's, shifted retries).
     *
     * @return whether the row was still pending
     */
    public boolean markFailed(UUID id, Duration retryIn, String error) {
        return jdbc.update("UPDATE " + table + " SET attempts = attempts + 1, "
                        + "next_attempt_at = now() + make_interval(secs => ?), "
                        + "last_error = ? WHERE id = ? AND status = 'PENDING'",
                retryIn.toMillis() / 1000.0, truncate(error), id) == 1;
    }

    public Backlog backlog() {
        return jdbc.queryForObject("SELECT count(*), extract(epoch FROM now() - min(created_at)) FROM " + table
                        + " WHERE status = 'PENDING'",
                (rs, n) -> {
                    final java.math.BigDecimal ageSeconds = rs.getObject(2, java.math.BigDecimal.class);
                    return new Backlog(rs.getLong(1), Optional.ofNullable(ageSeconds)
                            .map(age -> Duration.ofMillis(Math.max(0, age.movePointRight(3).longValue()))));
                });
    }

    /**
     * Deletes rows sent more than {@code retention} ago by the database's
     * clock, the one {@code sent_at} was written with, {@link #PURGE_CHUNK} per
     * statement, each committed on its own (no surrounding transaction).
     *
     * @return how many rows were deleted
     */
    public int purgeSentOlderThan(Duration retention) {
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update("DELETE FROM " + table + " WHERE id IN (SELECT id FROM " + table
                            + " WHERE status = 'SENT' AND sent_at < now() - make_interval(secs => ?) LIMIT "
                            + PURGE_CHUNK + ")",
                    retention.toMillis() / 1000.0);
            total += deleted;
        } while (deleted == PURGE_CHUNK);
        return total;
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }
}
