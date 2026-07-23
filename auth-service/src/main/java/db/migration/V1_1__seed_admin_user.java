package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.PreparedStatement;
import java.util.UUID;

/**
 * Seeds the bootstrap admin user on first deployment.
 *
 * <h2>Why a Java migration instead of SQL</h2>
 * SQL Flyway placeholders insert the raw value — a plain-text password would
 * appear in {@code flyway_schema_history}, PostgreSQL query logs, and the
 * version-controlled migration file itself. This Java migration reads the
 * password from an env var at runtime and BCrypt-hashes it in-process —
 * plain text never reaches the database or migration history.
 *
 * <p>Pre-hashing outside Flyway (e.g. via {@code htpasswd -nbBC 12}) is an
 * alternative but adds an error-prone manual step to every deployment.
 * The Java migration is self-contained and safer operationally.
 *
 * <h2>Environment variables</h2>
 * <ul>
 *   <li>{@code ADMIN_EMAIL} — login email (default: {@code admin@incidentplatform.com})</li>
 *   <li>{@code ADMIN_PASSWORD} — plain text, hashed at migration time
 *       (default: {@code changeme} — rotate immediately after first login)</li>
 *   <li>{@code ADMIN_TENANT_ID} — tenant identifier (default: {@code default})</li>
 * </ul>
 *
 * <h2>Idempotency</h2>
 * Both INSERTs use {@code ON CONFLICT DO NOTHING} — safe to re-run after a
 * schema repair without overwriting a password that was changed post-setup.
 *
 * <h2>Does NOT set users.version</h2>
 * This migration runs immediately after V1 — the `users.version` column
 * doesn't exist yet (it's added later, by V9__add_users_version_column.sql).
 * A previous edit added `version` to this INSERT when optimistic locking
 * was introduced, without moving this migration's position — since Flyway
 * always applies migrations in ascending version order on a fresh
 * database, that made this migration fail with "column version does not
 * exist" on every completely fresh database (out-of-order: true in
 * application.yml does not help here — it only permits a low-numbered
 * migration to apply after a higher one elsewhere; it doesn't reorder
 * execution within one migrate run).
 *
 * <p>Deliberately NOT fixed by renumbering this migration to run after V9
 * instead: doing so would rename the file, which Flyway's
 * validate-on-migrate would reject on any environment where version "1.1"
 * is already recorded as applied (`flyway_schema_history_auth`) — Flyway
 * refuses to start if a recorded migration's file can't be resolved.
 * Fixed in place instead: the row this migration creates simply doesn't
 * set `version` explicitly. When V9 runs afterward, its
 * `DEFAULT 0 NOT NULL` backfills version=0 onto this row along with every
 * other existing row — identical end state, without moving anything.
 */
public class V1_1__seed_admin_user extends BaseJavaMigration {

    private static final Logger log =
            LoggerFactory.getLogger(V1_1__seed_admin_user.class);

    private static final String DEFAULT_EMAIL = "admin@incidentplatform.com";
    private static final String DEFAULT_PASSWORD = "changeme";
    private static final String DEFAULT_TENANT = "default";
    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    @Override
    public void migrate(Context context) throws Exception {
        final String email = env("ADMIN_EMAIL", DEFAULT_EMAIL);
        final String password = env("ADMIN_PASSWORD", DEFAULT_PASSWORD);
        final String tenantId = env("ADMIN_TENANT_ID", DEFAULT_TENANT);

        if (DEFAULT_PASSWORD.equals(password)) {
            log.warn("Bootstrap admin is using the default password 'changeme'. " +
                    "Set ADMIN_PASSWORD and rotate immediately after first login.");
        }

        final UUID userId = UUID.randomUUID();
        // Argon2id — memory-hard, GPU-resistant. Same algorithm as SecurityConfig.
        // Note: Spring beans cannot be injected into Flyway Java migrations
        // (Flyway runs before the Spring context is fully initialized).
        // We instantiate directly — identical parameters to the @Bean in SecurityConfig.
        final PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        final String passwordHash = encoder.encode(password);

        // Check if admin already exists before inserting.
        // Cannot use ON CONFLICT because the unique constraint
        // uq_users_email_tenant_active is a partial index (WHERE archived_at IS NULL)
        // and PostgreSQL does not support ON CONFLICT targeting partial indexes.
        final String checkExists = """
        SELECT COUNT(*) FROM users WHERE email = ? AND tenant_id = ?
        """;
        boolean exists = false;
        try (PreparedStatement stmt =
                     context.getConnection().prepareStatement(checkExists)) {
            stmt.setString(1, email);
            stmt.setString(2, tenantId);
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    exists = rs.getLong(1) > 0;
                }
            }
        }
        if (!exists) {
            // No `version` column here — see class Javadoc. It doesn't exist
            // yet at this point in the migration sequence; V9 backfills it
            // with DEFAULT 0 for this row (and every other existing row)
            // once it runs.
            final String insertUser = """
            INSERT INTO users (id, tenant_id, email, password_hash, active)
            VALUES (?, ?, ?, ?, TRUE)
            """;
            try (PreparedStatement stmt =
                         context.getConnection().prepareStatement(insertUser)) {
                stmt.setObject(1, userId);
                stmt.setString(2, tenantId);
                stmt.setString(3, email);
                stmt.setString(4, passwordHash);
                stmt.executeUpdate();
            }
        }

        // Resolve the actual user id (may differ if ON CONFLICT skipped insert)
        final UUID resolvedUserId;
        final String selectId = """
                SELECT id FROM users WHERE email = ? AND tenant_id = ?
                """;

        try (PreparedStatement stmt =
                     context.getConnection().prepareStatement(selectId)) {
            stmt.setString(1, email);
            stmt.setString(2, tenantId);
            try (var rs = stmt.executeQuery()) {
                rs.next();
                resolvedUserId = UUID.fromString(rs.getString("id"));
            }
        }

        // Insert role
        final String insertRole = """
                INSERT INTO user_roles (id, user_id, tenant_id, role)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, tenant_id, role) DO NOTHING
                """;

        try (PreparedStatement stmt =
                     context.getConnection().prepareStatement(insertRole)) {
            stmt.setObject(1, UUID.randomUUID());
            stmt.setObject(2, resolvedUserId);
            stmt.setString(3, tenantId);
            stmt.setString(4, ROLE_ADMIN);
            stmt.executeUpdate();
        }

        log.info("Bootstrap admin seeded: email={}, tenant={}", email, tenantId);
    }

    private String env(String name, String defaultValue) {
        final String value = System.getenv(name);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}