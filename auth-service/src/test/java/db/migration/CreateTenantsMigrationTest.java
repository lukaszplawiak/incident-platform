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
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V21 (backlog #0-80) on a database that already has users: every tenant with
 * a user, archived ones included, gets a row; the oldest user's creation time
 * becomes the tenant's; an id longer than {@code display_name} allows still
 * migrates. Real Flyway, real Postgres, no Spring, as in
 * {@link ArchiveDefaultSeedAdminMigrationTest}: migrate to V20, insert the
 * existing data, then run V21.
 */
@Testcontainers
@DisplayName("V21 — tenants table backfilled from existing users (backlog #0-80)")
class CreateTenantsMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String LONG_TENANT = "t".repeat(230);
    private static final Instant OLDEST = Instant.parse("2025-01-02T03:04:05Z");

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .table("flyway_schema_history_auth")
                .target(MigrationVersion.fromVersion(target))
                .load();
    }

    @BeforeAll
    static void migrateWithExistingUsers() throws SQLException {
        flyway("20").migrate();
        try (Connection connection = connect()) {
            insertUser(connection, "acme", Instant.parse("2025-06-01T00:00:00Z"), false);
            insertUser(connection, "acme", OLDEST, false);
            insertUser(connection, "gone", Instant.parse("2025-03-01T00:00:00Z"), true);
            insertUser(connection, LONG_TENANT, Instant.parse("2025-04-01T00:00:00Z"), false);
        }
        flyway("21").migrate();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void insertUser(Connection connection, String tenantId, Instant createdAt,
                                   boolean archived) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("""
                INSERT INTO users (id, tenant_id, email, active, created_at, archived_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            stmt.setObject(1, UUID.randomUUID());
            stmt.setString(2, tenantId);
            stmt.setString(3, UUID.randomUUID() + "@example.com");
            stmt.setBoolean(4, !archived);
            stmt.setTimestamp(5, Timestamp.from(createdAt));
            stmt.setTimestamp(6, archived ? Timestamp.from(createdAt) : null);
            stmt.executeUpdate();
        }
    }

    @Test
    @DisplayName("one row per existing tenant, archived-only tenants included, no first admin recorded")
    void oneRowPerTenant() throws SQLException {
        try (Connection connection = connect(); ResultSet rs = connection.createStatement().executeQuery("""
                SELECT tenant_id, display_name, first_admin_email, created_by FROM tenants ORDER BY tenant_id
                """)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("tenant_id")).isEqualTo("acme");
            assertThat(rs.getString("display_name")).isEqualTo("acme");
            assertThat(rs.getString("first_admin_email")).isNull();
            assertThat(rs.getObject("created_by")).isNull();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("tenant_id")).isEqualTo("gone");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("tenant_id")).isEqualTo(LONG_TENANT);
            assertThat(rs.getString("display_name")).hasSize(200);
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    @DisplayName("a tenant's creation time is its oldest user's")
    void createdAtIsOldestUser() throws SQLException {
        try (Connection connection = connect(); ResultSet rs = connection.createStatement().executeQuery(
                "SELECT created_at FROM tenants WHERE tenant_id = 'acme'")) {
            rs.next();
            assertThat(rs.getTimestamp(1).toInstant()).isEqualTo(OLDEST);
        }
    }
}
