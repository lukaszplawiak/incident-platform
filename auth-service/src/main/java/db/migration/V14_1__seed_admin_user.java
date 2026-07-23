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
 * <h2>Renumbered from V1_1 to V14_1</h2>
 * Originally ran right after V1 (before the `users.version` column
 * existed). When optimistic locking was added, this migration's INSERT
 * was updated to populate `version`, but its Flyway version number was
 * never moved — Flyway always applies migrations in ascending version
 * order on a fresh database regardless of `out-of-order` (that setting
 * only permits a low-numbered migration to apply *after* a higher one
 * elsewhere; it does not reorder a single migrate run). The result: this
 * migration failed with "column version does not exist" on every
 * completely fresh database, from the moment the version column
 * reference was added, until now. Renumbered to run after V9 (which adds
 * `users.version`) — placed after V14 specifically, the last migration
 * that exists as of this fix, to avoid needing to verify every other
 * migration doesn't also touch something this one depends on.
 */
public class V14_1__seed_admin_user extends BaseJavaMigration {

    private static final Logger log =
            LoggerFactory.getLogger(V14_1__seed_admin_user.class);

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
            final String insertUser = """
            INSERT INTO users (id, tenant_id, email, password_hash, active, version)
            VALUES (?, ?, ?, ?, TRUE, 0)
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