package db.migration;

import db.migration.V20__archive_default_seed_admin.Result;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static db.migration.V20__archive_default_seed_admin.SEED_DEFAULT_EMAIL;
import static db.migration.V20__archive_default_seed_admin.SEED_DEFAULT_PASSWORD;
import static db.migration.V20__archive_default_seed_admin.SEED_DEFAULT_TENANT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Backlog #0-80: a new database gets no seeded admin, and on an existing one the
 * seeded admin is archived only while its password is still {@code changeme}.
 *
 * <p>Runs auth-service's real Flyway migrations against Postgres (the same image
 * as docker-compose), without Spring: what matters is the migration order
 * ({@code V1_1} now creates nothing, {@code V20} runs after every table it
 * touches exists) and the SQL against the real schema. Accounts the old
 * {@code V1_1} would have created are then inserted by hand and
 * {@link V20__archive_default_seed_admin#archiveIfDefaultPassword} is called on
 * them.
 */
@Testcontainers
@DisplayName("V1_1 / V20 — no seeded admin, default-password seed archived (backlog #0-80)")
class ArchiveDefaultSeedAdminMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Argon2PasswordEncoder ENCODER = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .table("flyway_schema_history_auth")
                .load()
                .migrate();
    }

    private Connection connection;

    @BeforeEach
    void connect() throws SQLException {
        connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (var stmt = connection.createStatement()) {
            stmt.executeUpdate("DELETE FROM api_keys");
            stmt.executeUpdate("DELETE FROM auth_tokens");
            stmt.executeUpdate("DELETE FROM user_roles");
            stmt.executeUpdate("DELETE FROM users");
        }
    }

    @Test
    @DisplayName("a new database has no user at all after every migration ran")
    void newDatabaseHasNoSeededAdmin() throws SQLException {
        // connect() emptied the tables, so check the migration history instead:
        // both versions are applied and a fresh schema run left no user behind.
        try (Connection fresh = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var rs = fresh.createStatement().executeQuery(
                     "SELECT count(*) FROM flyway_schema_history_auth "
                             + "WHERE version IN ('1.1', '20') AND success")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(2);
        }
        // A second, empty schema migrated from scratch proves V1_1 inserts nothing.
        try (var stmt = connection.createStatement()) {
            stmt.execute("CREATE SCHEMA fresh");
        }
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("fresh")
                .locations("classpath:db/migration")
                .table("flyway_schema_history_auth")
                .load()
                .migrate();
        assertThat(count("SELECT count(*) FROM fresh.users")).isZero();
        try (var stmt = connection.createStatement()) {
            stmt.execute("DROP SCHEMA fresh CASCADE");
        }
    }

    @Test
    @DisplayName("seeded admin with the default password: archived, deactivated, tokens invalidated")
    void defaultPasswordIsArchived() throws SQLException {
        final UUID id = insertSeededAdmin(ENCODER.encode(SEED_DEFAULT_PASSWORD));
        insertUnusedToken(id);
        insertPersonalApiKey(id);

        final Result result = V20__archive_default_seed_admin.archiveIfDefaultPassword(
                connection, SEED_DEFAULT_EMAIL, SEED_DEFAULT_TENANT);

        assertThat(result).isEqualTo(Result.ARCHIVED);
        assertThat(count("SELECT count(*) FROM api_keys WHERE owner_user_id = '" + id
                + "' AND revoked_at IS NULL")).as("its personal API keys are revoked").isZero();
        assertThat(count("SELECT count(*) FROM users WHERE id = '" + id
                + "' AND archived_at IS NOT NULL AND active = FALSE AND version = 1")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM auth_tokens WHERE user_id = '" + id
                + "' AND used_at IS NULL")).isZero();
    }

    @Test
    @DisplayName("seeded admin whose password was changed: left as it is")
    void changedPasswordIsKept() throws SQLException {
        final UUID id = insertSeededAdmin(ENCODER.encode("a-password-someone-chose"));
        insertUnusedToken(id);

        final Result result = V20__archive_default_seed_admin.archiveIfDefaultPassword(
                connection, SEED_DEFAULT_EMAIL, SEED_DEFAULT_TENANT);

        assertThat(result).isEqualTo(Result.PASSWORD_CHANGED);
        assertThat(count("SELECT count(*) FROM users WHERE id = '" + id
                + "' AND archived_at IS NULL AND active = TRUE")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM auth_tokens WHERE user_id = '" + id
                + "' AND used_at IS NULL")).isEqualTo(1);
    }

    @Test
    @DisplayName("seeded admin with a non-Argon2 hash (e.g. bcrypt): left as it is, no exception")
    void foreignHashIsKept() throws SQLException {
        final UUID id = insertSeededAdmin(
                "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy");
        insertPersonalApiKey(id);

        assertThat(V20__archive_default_seed_admin.archiveIfDefaultPassword(
                connection, SEED_DEFAULT_EMAIL, SEED_DEFAULT_TENANT)).isEqualTo(Result.PASSWORD_CHANGED);
        assertThat(count("SELECT count(*) FROM users WHERE id = '" + id
                + "' AND archived_at IS NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM api_keys WHERE owner_user_id = '" + id
                + "' AND revoked_at IS NULL")).isEqualTo(1);
    }

    @Test
    @DisplayName("seeded admin with no password: left as it is")
    void noPasswordIsKept() throws SQLException {
        final UUID id = insertSeededAdmin(null);

        assertThat(V20__archive_default_seed_admin.archiveIfDefaultPassword(
                connection, SEED_DEFAULT_EMAIL, SEED_DEFAULT_TENANT)).isEqualTo(Result.PASSWORD_CHANGED);
        assertThat(count("SELECT count(*) FROM users WHERE id = '" + id
                + "' AND archived_at IS NULL")).isEqualTo(1);
    }

    @Test
    @DisplayName("no such account, or only another tenant's user with that email: nothing changes")
    void otherTenantIsNotTouched() throws SQLException {
        final UUID other = insertUser(SEED_DEFAULT_EMAIL, "acme", ENCODER.encode(SEED_DEFAULT_PASSWORD));

        final Result result = V20__archive_default_seed_admin.archiveIfDefaultPassword(
                connection, SEED_DEFAULT_EMAIL, SEED_DEFAULT_TENANT);

        assertThat(result).isEqualTo(Result.NOT_FOUND);
        assertThat(count("SELECT count(*) FROM users WHERE id = '" + other
                + "' AND archived_at IS NULL")).isEqualTo(1);
    }

    @Test
    @DisplayName("an already archived seeded admin is not touched again")
    void alreadyArchivedIsNotFound() throws SQLException {
        final UUID id = insertSeededAdmin(ENCODER.encode(SEED_DEFAULT_PASSWORD));
        try (var stmt = connection.createStatement()) {
            stmt.executeUpdate("UPDATE users SET archived_at = now() - interval '1 day', active = FALSE "
                    + "WHERE id = '" + id + "'");
        }

        assertThat(V20__archive_default_seed_admin.archiveIfDefaultPassword(
                connection, SEED_DEFAULT_EMAIL, SEED_DEFAULT_TENANT)).isEqualTo(Result.NOT_FOUND);
        assertThat(count("SELECT count(*) FROM users WHERE id = '" + id
                + "' AND archived_at < now() - interval '1 hour'")).isEqualTo(1);
    }

    /** As the old V1_1 created it: an active user plus a ROLE_ADMIN row. */
    private UUID insertSeededAdmin(String passwordHash) throws SQLException {
        final UUID id = insertUser(SEED_DEFAULT_EMAIL, SEED_DEFAULT_TENANT, passwordHash);
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO user_roles (id, user_id, tenant_id, role) VALUES (?, ?, ?, 'ROLE_ADMIN')")) {
            stmt.setObject(1, UUID.randomUUID());
            stmt.setObject(2, id);
            stmt.setString(3, SEED_DEFAULT_TENANT);
            stmt.executeUpdate();
        }
        return id;
    }

    private UUID insertUser(String email, String tenantId, String passwordHash) throws SQLException {
        // Backlog #0-82: a user needs its tenant's row (V31's foreign key).
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO tenants (tenant_id, display_name) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            stmt.setString(1, tenantId);
            stmt.setString(2, tenantId);
            stmt.executeUpdate();
        }
        final UUID id = UUID.randomUUID();
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO users (id, tenant_id, email, password_hash, active) VALUES (?, ?, ?, ?, TRUE)")) {
            stmt.setObject(1, id);
            stmt.setString(2, tenantId);
            stmt.setString(3, email);
            stmt.setString(4, passwordHash);
            stmt.executeUpdate();
        }
        return id;
    }

    private void insertPersonalApiKey(UUID ownerId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("""
                INSERT INTO api_keys (id, tenant_id, key_type, name, key_hash, key_prefix, owner_user_id)
                VALUES (?, ?, 'PERSONAL', 'cli', ?, 'ipl_abcd', ?)
                """)) {
            stmt.setObject(1, UUID.randomUUID());
            stmt.setString(2, SEED_DEFAULT_TENANT);
            stmt.setString(3, UUID.randomUUID().toString().replace("-", "") + "0".repeat(32));
            stmt.setObject(4, ownerId);
            stmt.executeUpdate();
        }
    }

    private void insertUnusedToken(UUID userId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("""
                INSERT INTO auth_tokens (id, user_id, tenant_id, token_hash, type, expires_at, created_at)
                VALUES (?, ?, ?, ?, 'REFRESH', now() + interval '1 day', now())
                """)) {
            stmt.setObject(1, UUID.randomUUID());
            stmt.setObject(2, userId);
            stmt.setString(3, SEED_DEFAULT_TENANT);
            stmt.setString(4, UUID.randomUUID().toString());
            stmt.executeUpdate();
        }
    }

    private int count(String sql) throws SQLException {
        try (var stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
