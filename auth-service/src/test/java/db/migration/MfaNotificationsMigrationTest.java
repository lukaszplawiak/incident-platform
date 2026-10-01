package db.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V23 (backlog #0-83) on a database that already has users: a factor enabled
 * at least 24 hours before the migration is taken over as established (its
 * notice time is its enrolment time), so its owner neither loses it on a
 * password reset nor has to re-enrol for the platform API; a factor enabled
 * more recently is not, so a password thief's fresh factor still gets the
 * grace period; an operator admin's factor is not taken over at any age; a
 * user without MFA gets nothing; the outbox
 * accepts the new notification types. Real Flyway and Postgres, as in
 * {@link CreateTenantsMigrationTest}: migrate to V22, insert, then run V23.
 */
@Testcontainers
@DisplayName("V23 — MFA notification types and backfill of existing factors (backlog #0-83)")
class MfaNotificationsMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Instant ENABLED_AT = Instant.parse("2026-05-01T10:00:00Z");
    private static final UUID WITH_MFA = UUID.randomUUID();
    private static final UUID WITHOUT_MFA = UUID.randomUUID();
    private static final UUID RECENT_MFA = UUID.randomUUID();
    private static final UUID JUST_ESTABLISHED_MFA = UUID.randomUUID();
    private static final UUID OPERATOR_MFA = UUID.randomUUID();
    private static final Instant MIGRATION_STARTS = Instant.now();

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .table("flyway_schema_history_auth")
                .target(MigrationVersion.fromVersion(target))
                .load();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @BeforeAll
    static void migrateWithExistingFactors() throws SQLException {
        flyway("22").migrate();
        try (Connection connection = connect(); PreparedStatement stmt = connection.prepareStatement("""
                INSERT INTO users (id, tenant_id, email, active, mfa_enabled, mfa_enabled_at)
                VALUES (?, 'acme', ?, TRUE, ?, ?)
                """)) {
            stmt.setObject(1, WITH_MFA);
            stmt.setString(2, "mfa@acme.example");
            stmt.setBoolean(3, true);
            stmt.setTimestamp(4, Timestamp.from(ENABLED_AT));
            stmt.executeUpdate();
            stmt.setObject(1, WITHOUT_MFA);
            stmt.setString(2, "plain@acme.example");
            stmt.setBoolean(3, false);
            stmt.setTimestamp(4, null);
            stmt.executeUpdate();
            // Hours of margin either side of the 24 h cutoff, so the test does
            // not depend on how long the migration takes.
            stmt.setObject(1, RECENT_MFA);
            stmt.setString(2, "recent@acme.example");
            stmt.setBoolean(3, true);
            stmt.setTimestamp(4, Timestamp.from(MIGRATION_STARTS.minus(Duration.ofHours(20))));
            stmt.executeUpdate();
            stmt.setObject(1, JUST_ESTABLISHED_MFA);
            stmt.setString(2, "established@acme.example");
            stmt.setBoolean(3, true);
            stmt.setTimestamp(4, Timestamp.from(MIGRATION_STARTS.minus(Duration.ofHours(28))));
            stmt.executeUpdate();
        }
        try (Connection connection = connect(); PreparedStatement stmt = connection.prepareStatement("""
                INSERT INTO users (id, tenant_id, email, active, mfa_enabled, mfa_enabled_at)
                VALUES (?, 'platform-operator', 'ops@platform.example', TRUE, TRUE, ?)
                """)) {
            stmt.setObject(1, OPERATOR_MFA);
            stmt.setTimestamp(2, Timestamp.from(ENABLED_AT));
            stmt.executeUpdate();
        }
        flyway("23").migrate();
    }

    private static Timestamp noticeSentAt(UUID userId) throws SQLException {
        try (Connection connection = connect(); PreparedStatement stmt = connection.prepareStatement(
                "SELECT mfa_enabled_notice_sent_at FROM users WHERE id = ?")) {
            stmt.setObject(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1);
            }
        }
    }

    @Test
    @DisplayName("a factor older than 24 h counts as established from its enrolment; a user without MFA gets nothing")
    void backfill() throws SQLException {
        assertThat(noticeSentAt(WITH_MFA).toInstant()).isEqualTo(ENABLED_AT);
        assertThat(noticeSentAt(JUST_ESTABLISHED_MFA)).isNotNull();
        assertThat(noticeSentAt(WITHOUT_MFA)).isNull();
    }

    @Test
    @DisplayName("a factor enabled within the last 24 h is not backfilled, so it still gets the grace period")
    void recentFactorNotBackfilled() throws SQLException {
        assertThat(noticeSentAt(RECENT_MFA)).isNull();
    }

    @Test
    @DisplayName("an operator admin's factor is not backfilled at any age: the operator re-enrols")
    void operatorFactorNotBackfilled() throws SQLException {
        assertThat(noticeSentAt(OPERATOR_MFA)).isNull();
    }

    @Test
    @DisplayName("the outbox accepts MFA_ENABLED and MFA_DISABLED and still refuses an unknown type")
    void outboxTypes() throws SQLException {
        try (Connection connection = connect()) {
            for (final String type : new String[] {"MFA_ENABLED", "MFA_DISABLED"}) {
                insertOutbox(connection, type);
            }
            // Autocommit: each insert is its own transaction, so the refused
            // one does not undo the two above.
            assertThatThrownBy(() -> insertOutbox(connection, "SOMETHING_ELSE"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_auth_email_outbox_type");
        }
    }

    private static void insertOutbox(Connection connection, String type) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("""
                INSERT INTO auth_email_outbox (id, user_id, tenant_id, email, email_type, status,
                                               created_at, deadline, next_attempt_at)
                VALUES (?, ?, 'acme', 'mfa@acme.example', ?, 'PENDING', now(), now() + INTERVAL '1 day', now())
                """)) {
            stmt.setObject(1, UUID.randomUUID());
            stmt.setObject(2, WITH_MFA);
            stmt.setString(3, type);
            stmt.executeUpdate();
        }
    }
}
