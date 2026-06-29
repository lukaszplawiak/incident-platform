package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.sql.PreparedStatement;
import java.util.UUID;

/**
 * Seeds the bootstrap admin user on first deployment.
 *
 * <h2>Why a Java migration instead of SQL with placeholders</h2>
 * Flyway SQL placeholders pass the raw value into the script — the plain-text
 * password would appear in {@code flyway_schema_history}, query logs, and
 * version-controlled migration files. This Java migration reads the password
 * from an environment variable at runtime and BCrypt-hashes it before
 * inserting — plain text never touches the database or migration history.
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
        final String passwordHash = new BCryptPasswordEncoder().encode(password);

        // Insert user
        final String insertUser = """
                INSERT INTO users (id, tenant_id, email, password_hash, active)
                VALUES (?, ?, ?, ?, TRUE)
                ON CONFLICT (email, tenant_id) DO NOTHING
                """;

        try (PreparedStatement stmt =
                     context.getConnection().prepareStatement(insertUser)) {
            stmt.setObject(1, userId);
            stmt.setString(2, tenantId);
            stmt.setString(3, email);
            stmt.setString(4, passwordHash);
            stmt.executeUpdate();
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