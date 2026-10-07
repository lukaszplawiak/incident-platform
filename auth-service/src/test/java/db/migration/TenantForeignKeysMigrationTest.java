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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V31 and V32 (backlog #0-82) on a database whose tenant data was joined to
 * {@code tenants} by convention only: a tenant id with data but no row (in
 * tables other than users, which V21 backfilled) gets an ACTIVE row named
 * after it, a tenant that already had its row keeps it as it was, and from
 * then on the foreign keys refuse data for an unknown tenant and the removal
 * of a tenant row that still has data. Real Flyway, real Postgres, no Spring,
 * as in {@link CreateTenantsMigrationTest}: migrate to V30, insert the
 * existing data, run V31, check the window before V32, then run V32.
 *
 * <p>Each covered table holds the only row of its own orphaned tenant id
 * (review: two tables seeded left seven branches of V31's UNION untested), so
 * a table missing from the backfill leaves its id without a row, and V32
 * fails on it.
 */
@Testcontainers
@DisplayName("V31/V32 — every tenant's data references its tenants row (backlog #0-82)")
class TenantForeignKeysMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .table("flyway_schema_history_auth")
                .target(MigrationVersion.fromVersion(target))
                .load();
    }

    /** The nine covered tables, each holding the one row of {@code orphan-<table>}. */
    private static final List<String> COVERED = List.of("users", "user_roles", "teams", "tenant_settings",
            "auth_tokens", "api_keys", "integrations", "slack_workspaces", "mfa_recovery_requests");

    private static final List<String> flywayLog = new ArrayList<>();
    private static boolean refusedBeforeValidation;
    private static int validatedBeforeV32;

    @BeforeAll
    static void migrateWithOrphanedData() throws SQLException {
        flyway("30").migrate();
        try (Connection connection = connect()) {
            update(connection, "INSERT INTO tenants (tenant_id, display_name) VALUES ('named', 'Named Corp')");
            update(connection, "INSERT INTO teams (tenant_id, name) VALUES ('named', 'ops')");
            // A recorded tenant's user, for the rows below that need one; their
            // own tenant_id is the orphan (the column is not tied to the user's).
            update(connection, "INSERT INTO users (id, tenant_id, email) "
                    + "VALUES ('00000000-0000-0000-0000-000000000001', 'named', 'a@named.example')");
            final String user = "'00000000-0000-0000-0000-000000000001'";
            update(connection, "INSERT INTO users (tenant_id, email) VALUES ('orphan-users', 'b@orphan.example')");
            update(connection, "INSERT INTO user_roles (user_id, tenant_id, role) "
                    + "VALUES (" + user + ", 'orphan-user-roles', 'ROLE_ADMIN')");
            update(connection, "INSERT INTO teams (tenant_id, name) VALUES ('orphan-teams', 'ops')");
            update(connection, "INSERT INTO tenant_settings (tenant_id) VALUES ('orphan-tenant-settings')");
            // A reserved id is adopted too, and flagged in its warning.
            update(connection, "INSERT INTO teams (tenant_id, name) VALUES ('system', 'legacy')");
            update(connection, "INSERT INTO auth_tokens (user_id, tenant_id, token_hash, type, expires_at) "
                    + "VALUES (" + user + ", 'orphan-auth-tokens', 'hash', 'REFRESH', now() + interval '1 day')");
            update(connection, "INSERT INTO api_keys (tenant_id, key_type, name, key_hash, key_prefix) "
                    + "VALUES ('orphan-api-keys', 'TENANT', 'k', '" + "a".repeat(64) + "', 'ipl_aaaa')");
            update(connection, "INSERT INTO integrations (tenant_id, name, source) "
                    + "VALUES ('orphan-integrations', 'prom', 'prometheus')");
            update(connection, "INSERT INTO slack_workspaces (tenant_id, slack_team_id, bot_token_encrypted) "
                    + "VALUES ('orphan-slack-workspaces', 'T0123', 'iv:ct')");
            update(connection, "INSERT INTO mfa_recovery_requests (id, tenant_id, user_id, requested_by, "
                    + "verification_method, verification_note, status, created_at) VALUES (gen_random_uuid(), "
                    + "'orphan-mfa-recovery-requests', " + user + ", " + user + ", 'VIDEO_CALL', 'n', 'PENDING', now())");
        }
        final ch.qos.logback.classic.Logger flywayLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.flywaydb");
        final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        flywayLogger.addAppender(appender);
        try {
            flyway("31").migrate();
        } finally {
            flywayLogger.detachAppender(appender);
        }
        appender.list.forEach(event -> flywayLog.add(event.getFormattedMessage()));
        // Between V31 and V32 (review): the keys are not validated yet, and
        // already refuse a new row for an unknown tenant.
        try (Connection connection = connect()) {
            try {
                update(connection, "INSERT INTO teams (tenant_id, name) VALUES ('between-migrations', 'ops')");
            } catch (SQLException refused) {
                refusedBeforeValidation = refused.getMessage().contains("fk_teams_tenant");
            }
            try (ResultSet rs = connection.createStatement().executeQuery(
                    "SELECT count(*) FROM pg_constraint WHERE contype = 'f' AND convalidated "
                            + "AND confrelid = 'tenants'::regclass")) {
                rs.next();
                validatedBeforeV32 = rs.getInt(1);
            }
        }
        flyway("32").migrate();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void update(Connection connection, String sql) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.executeUpdate();
        }
    }

    @Test
    @DisplayName("a tenant id with data but no row gets an ACTIVE row named after it; a recorded one is untouched")
    void orphanedTenantIdsRecorded() throws SQLException {
        final List<String> rows = new ArrayList<>();
        try (Connection connection = connect(); ResultSet rs = connection.createStatement().executeQuery(
                "SELECT tenant_id, display_name, status FROM tenants ORDER BY tenant_id")) {
            while (rs.next()) {
                rows.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3));
            }
        }
        final List<String> expected = new ArrayList<>(List.of("named|Named Corp|ACTIVE", "system|system|ACTIVE"));
        for (final String table : COVERED) {
            final String id = "orphan-" + table.replace('_', '-');
            expected.add(id + "|" + id + "|ACTIVE");
        }
        assertThat(rows).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("each adopted id is named in a warning in the log, and only those (review)")
    void adoptedIdsLogged() {
        final List<String> warnings = flywayLog.stream().filter(line -> line.contains("V31: tenant ")).toList();
        assertThat(warnings).hasSize(COVERED.size() + 1);
        assertThat(warnings).anySatisfy(line -> assertThat(line)
                .contains("orphan-slack-workspaces had data but no tenants row; recorded as ACTIVE")
                .doesNotContain("reserved"));
        assertThat(warnings).anySatisfy(line -> assertThat(line)
                .contains("tenant system had data but no tenants row; recorded as ACTIVE (a reserved tenant id)"));
        assertThat(warnings).noneMatch(line -> line.contains("V31: tenant named "));
    }

    @Test
    @DisplayName("between V31 and V32 the keys are not validated yet, and already refuse a new orphan")
    void notValidKeysAlreadyRefuseNewRows() {
        assertThat(validatedBeforeV32).isZero();
        assertThat(refusedBeforeValidation).isTrue();
    }

    @Test
    @DisplayName("V32 leaves every foreign key to tenants validated")
    void foreignKeysValidated() throws SQLException {
        try (Connection connection = connect(); ResultSet rs = connection.createStatement().executeQuery("""
                SELECT count(*) FILTER (WHERE convalidated), count(*) FROM pg_constraint
                WHERE contype = 'f' AND confrelid = 'tenants'::regclass
                """)) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(9).isEqualTo(rs.getInt(2));
        }
    }

    @Test
    @DisplayName("data for a tenant without a row is refused")
    void unknownTenantRefused() throws SQLException {
        try (Connection connection = connect()) {
            assertThatThrownBy(() -> update(connection,
                    "INSERT INTO teams (tenant_id, name) VALUES ('never-recorded', 'ops')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("fk_teams_tenant");
        }
    }

    @Test
    @DisplayName("a tenants row with data cannot be deleted (ON DELETE RESTRICT)")
    void tenantRowWithDataKept() throws SQLException {
        try (Connection connection = connect()) {
            // 'named' holds a team and a user: whichever key Postgres checks first refuses it.
            assertThatThrownBy(() -> update(connection, "DELETE FROM tenants WHERE tenant_id = 'named'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageMatching("(?s).*violates foreign key constraint \"fk_(users|teams)_tenant\".*");
        }
    }
}
