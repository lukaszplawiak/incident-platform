package com.incidentplatform.shared.audit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit outbox SQL on a real Postgres (backlog #0-84): written with the
 * caller's transaction, read in the order of its due index, marked by the
 * relay, purged in chunks. The table is the one each service's migration
 * creates. Joining a JPA transaction is checked in the services
 * ({@code AuthRepositoryIntegrationTest}, {@code AuditPersistenceIntegrationTest}).
 */
@DisplayName("AuditOutbox (Postgres)")
class AuditOutboxTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String TABLE = "test_audit_outbox";

    private static JdbcTemplate jdbc;
    private static TransactionTemplate callerTransaction;
    private static AuditOutbox outbox;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        final SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.postgresql.Driver(),
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        final DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        callerTransaction = new TransactionTemplate(transactionManager);
        outbox = new AuditOutbox(jdbc, TABLE);
        // The shape every service's migration creates.
        jdbc.execute("""
                CREATE TABLE test_audit_outbox (
                    id              UUID PRIMARY KEY,
                    tenant_id       VARCHAR(255) NOT NULL,
                    event_type      VARCHAR(100) NOT NULL,
                    payload         TEXT NOT NULL,
                    status          VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'SENT')),
                    attempts        INTEGER NOT NULL DEFAULT 0,
                    last_error      VARCHAR(1000),
                    created_at      TIMESTAMPTZ NOT NULL,
                    next_attempt_at TIMESTAMPTZ NOT NULL,
                    sent_at         TIMESTAMPTZ
                )""");
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM " + TABLE);
    }

    private long rows() {
        return jdbc.queryForObject("SELECT count(*) FROM " + TABLE, Long.class);
    }

    @Test
    @DisplayName("written in the caller's transaction: a rollback takes the event with the action")
    void rolledBackWithCaller() {
        callerTransaction.executeWithoutResult(status -> {
            outbox.enqueue(UUID.randomUUID(), "acme", "USER_LOGIN", "{}");
            status.setRollbackOnly();
        });

        assertThat(rows()).isZero();
    }

    @Test
    @DisplayName("an empty outbox has no oldest age")
    void emptyBacklog() {
        assertThat(outbox.backlog()).isEqualTo(new AuditOutbox.Backlog(0, java.util.Optional.empty()));
    }

    @Test
    @DisplayName("written in the caller's transaction: a commit keeps it pending")
    void committedWithCaller() {
        callerTransaction.executeWithoutResult(status ->
                outbox.enqueue(UUID.randomUUID(), "acme", "USER_LOGIN", "{}"));

        assertThat(rows()).isEqualTo(1);
        assertThat(outbox.backlog().pending()).isEqualTo(1);
    }

    @Test
    @DisplayName("due rows come in the order they became due, at most the limit, only once their retry time came")
    void dueOrderAndRetryTime() {
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();
        final UUID retried = UUID.randomUUID();
        final UUID later = UUID.randomUUID();
        outbox.enqueue(first, "acme", "A", "{1}");
        outbox.enqueue(second, "acme", "B", "{2}");
        outbox.enqueue(retried, "acme", "R", "{r}");
        outbox.enqueue(later, "acme", "C", "{3}");
        // Never tried: due since written.
        jdbc.update("UPDATE " + TABLE + " SET created_at = now() - INTERVAL '2 minutes', "
                + "next_attempt_at = now() - INTERVAL '2 minutes' WHERE id = ?", first);
        jdbc.update("UPDATE " + TABLE + " SET created_at = now() - INTERVAL '1 minute', "
                + "next_attempt_at = now() - INTERVAL '1 minute' WHERE id = ?", second);
        // Written first, failed, due again only after the other two.
        jdbc.update("UPDATE " + TABLE + " SET created_at = now() - INTERVAL '3 minutes' WHERE id = ?", retried);
        outbox.markFailed(retried, Duration.ofSeconds(-30), "broker down");
        outbox.markFailed(later, Duration.ofHours(1), "broker down");

        assertThat(outbox.due(10)).extracting(AuditOutbox.Pending::id).containsExactly(first, second, retried);
        assertThat(outbox.due(1)).extracting(AuditOutbox.Pending::id).containsExactly(first);
    }

    @Test
    @DisplayName("a failed row is due again after the delay, by the database's clock")
    void retryByDatabaseClock() {
        final UUID id = UUID.randomUUID();
        outbox.enqueue(id, "acme", "A", "{}");

        outbox.markFailed(id, Duration.ofSeconds(20), "down");

        assertThat(jdbc.queryForObject("SELECT extract(epoch FROM next_attempt_at - now()) FROM " + TABLE
                + " WHERE id = ?", Double.class, id)).isBetween(19.0, 20.5);
        assertThat(outbox.due(10)).isEmpty();
    }

    @Test
    @DisplayName("a row already sent is not marked failed")
    void sentRowNotMarkedFailed() {
        final UUID id = UUID.randomUUID();
        outbox.enqueue(id, "acme", "A", "{}");
        outbox.markSent(List.of(id));

        assertThat(outbox.markFailed(id, Duration.ofSeconds(5), "late")).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM " + TABLE + " WHERE id = ?", String.class, id))
                .isEqualTo("SENT");
    }

    @Test
    @DisplayName("purge deletes in chunks until no old sent row is left")
    void purgeInChunks() {
        final int rows = AuditOutbox.PURGE_CHUNK + 7;
        jdbc.update("INSERT INTO " + TABLE + " (id, tenant_id, event_type, payload, status, attempts, "
                + "created_at, next_attempt_at, sent_at) SELECT gen_random_uuid(), 'acme', 'A', '{}', 'SENT', 1, "
                + "now(), now(), now() - INTERVAL '8 days' FROM generate_series(1, ?)", rows);

        assertThat(outbox.purgeSentOlderThan(Duration.ofDays(7))).isEqualTo(rows);
        assertThat(rows()).isZero();
    }

    @Test
    @DisplayName("marked sent once; a failure counts the attempt, keeps the error and waits")
    void markSentAndFailed() {
        final UUID id = UUID.randomUUID();
        outbox.enqueue(id, "acme", "A", "{}");

        assertThat(outbox.markFailed(id, Duration.ofSeconds(-1), "x".repeat(2000))).isTrue();
        assertThat(jdbc.queryForObject("SELECT attempts FROM " + TABLE + " WHERE id = ?", Integer.class, id))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT length(last_error) FROM " + TABLE + " WHERE id = ?",
                Integer.class, id)).as("error cut to the column").isEqualTo(1000);

        assertThat(outbox.markSent(List.of(id))).isEqualTo(1);
        assertThat(outbox.markSent(List.of(id))).as("one writer: a second mark finds nothing pending").isZero();
        assertThat(outbox.markSent(List.of())).isZero();
        assertThat(outbox.due(10)).isEmpty();
        assertThat(outbox.backlog().pending()).isZero();
    }

    @Test
    @DisplayName("backlog counts pending rows and the oldest; purge deletes only old sent rows")
    void backlogAndPurge() {
        final UUID pending = UUID.randomUUID();
        final UUID oldSent = UUID.randomUUID();
        final UUID newSent = UUID.randomUUID();
        outbox.enqueue(pending, "acme", "A", "{}");
        outbox.enqueue(oldSent, "acme", "B", "{}");
        outbox.enqueue(newSent, "acme", "C", "{}");
        assertThat(outbox.markSent(List.of(oldSent, newSent))).as("one statement for a batch").isEqualTo(2);
        jdbc.update("UPDATE " + TABLE + " SET sent_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(8, ChronoUnit.DAYS)), oldSent);

        jdbc.update("UPDATE " + TABLE + " SET created_at = now() - INTERVAL '2 minutes' WHERE id = ?", pending);

        final AuditOutbox.Backlog backlog = outbox.backlog();
        assertThat(backlog.pending()).isEqualTo(1);
        assertThat(backlog.oldestAge()).as("by the database's clock").hasValueSatisfying(age ->
                assertThat(age).isBetween(Duration.ofSeconds(119), Duration.ofSeconds(125)));

        assertThat(outbox.purgeSentOlderThan(Duration.ofDays(7))).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id FROM " + TABLE, UUID.class))
                .containsExactlyInAnyOrder(pending, newSent);
    }
}
