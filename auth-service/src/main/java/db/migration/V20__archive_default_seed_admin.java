package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Archives the admin account the old {@code V1_1__seed_admin_user} created, where
 * its password is still the published default {@code changeme} (backlog #0-80).
 *
 * <p>{@code V1_1} no longer creates the account, but on a database where it ran
 * the account exists: a {@code ROLE_ADMIN} user anyone could log in as. This
 * migration finds it the way {@code V1_1} chose it ({@code ADMIN_EMAIL} /
 * {@code ADMIN_TENANT_ID}, defaulting to {@code admin@incidentplatform.com} /
 * {@code default}) and, only if its password still verifies as {@code changeme}
 * (Argon2), archives it as {@code User.archive()} does ({@code archived_at},
 * {@code active = false}), invalidates its unused tokens, so no refresh token
 * outlives it, and revokes its API keys, as archiving through the API does.
 *
 * <p>An account whose password was changed is left alone, with a WARN: someone
 * may use it, and its password is no longer public. An already archived account
 * is not touched. A tenant without an admin then gets one the way every
 * customer tenant does: a platform operator provisions it and invites its first
 * admin (docs/tenant-provisioning.md). The tenant itself already exists (V21
 * backfills it without a first admin), so for {@code default} the operator
 * either provisions a new tenant or a tenant admin is restored by hand as that
 * guide describes.
 *
 * <p>Runs once per database, like every versioned migration. A Flyway migration
 * cannot publish audit events (Spring is not up yet), so the outcome is logged.
 * Verifying an Argon2 hash costs a few tens of milliseconds, done for at most one
 * row.
 */
public class V20__archive_default_seed_admin extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V20__archive_default_seed_admin.class);

    static final String SEED_DEFAULT_EMAIL = "admin@incidentplatform.com";
    static final String SEED_DEFAULT_TENANT = "default";
    static final String SEED_DEFAULT_PASSWORD = "changeme";

    /** What the migration found for the seeded account. */
    enum Result { NOT_FOUND, ARCHIVED, PASSWORD_CHANGED }

    @Override
    public void migrate(Context context) throws SQLException {
        final String email = env("ADMIN_EMAIL", SEED_DEFAULT_EMAIL);
        final String tenantId = env("ADMIN_TENANT_ID", SEED_DEFAULT_TENANT);
        final Result result = archiveIfDefaultPassword(context.getConnection(), email, tenantId);
        if (result == Result.NOT_FOUND
                && (!email.equals(SEED_DEFAULT_EMAIL) || !tenantId.equals(SEED_DEFAULT_TENANT))) {
            // V1_1 chose its account from these variables when it ran, possibly
            // long ago. If they were changed since, this run looked for the wrong
            // account, and as a versioned migration it never runs again (found in
            // review).
            log.warn("No seeded admin found under ADMIN_EMAIL / ADMIN_TENANT_ID as set now ({} in "
                    + "tenant {}). If V1_1 ran with other values, check for that account by hand "
                    + "(docs/tenant-provisioning.md, backlog #0-80)", email, tenantId);
        }
    }

    static Result archiveIfDefaultPassword(Connection connection, String email, String tenantId)
            throws SQLException {
        UUID userId = null;
        String passwordHash = null;
        try (PreparedStatement stmt = connection.prepareStatement("""
                SELECT id, password_hash FROM users
                WHERE email = ? AND tenant_id = ? AND archived_at IS NULL AND anonymized_at IS NULL
                """)) {
            stmt.setString(1, email);
            stmt.setString(2, tenantId);
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    userId = rs.getObject("id", UUID.class);
                    passwordHash = rs.getString("password_hash");
                }
            }
        }
        if (userId == null) {
            log.info("No active seeded admin {} in tenant {} — nothing to archive (backlog #0-80)",
                    email, tenantId);
            return Result.NOT_FOUND;
        }
        // Same encoder as V1_1 and SecurityConfig; Spring beans are not
        // available inside a Flyway migration.
        final PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        if (passwordHash == null) {
            // Nobody can log in to it with a password: not what V1_1 wrote.
            log.warn("Seeded admin {} in tenant {} (id={}) has no password — left as it is. Check "
                    + "it is still wanted (backlog #0-80)", email, tenantId, userId);
            return Result.PASSWORD_CHANGED;
        }
        if (!encoder.matches(SEED_DEFAULT_PASSWORD, passwordHash)) {
            log.warn("Seeded admin {} in tenant {} (id={}) no longer has the default password — "
                    + "left as it is. Check it is still wanted (backlog #0-80)", email, tenantId, userId);
            return Result.PASSWORD_CHANGED;
        }
        // version + 1 as JPA's @Version would: an instance still holding the
        // user (a replica of the previous release during a rolling deploy)
        // then fails its save instead of overwriting the archive (found in review).
        try (PreparedStatement stmt = connection.prepareStatement("""
                UPDATE users SET archived_at = now(), active = FALSE, updated_at = now(),
                                 version = version + 1
                WHERE id = ?
                """)) {
            stmt.setObject(1, userId);
            stmt.executeUpdate();
        }
        final int tokens;
        try (PreparedStatement stmt = connection.prepareStatement("""
                UPDATE auth_tokens SET used_at = now() WHERE user_id = ? AND used_at IS NULL
                """)) {
            stmt.setObject(1, userId);
            tokens = stmt.executeUpdate();
        }
        // As archiving through the API does (ApiKeyRepository.revokeAllPersonalKeysForUser):
        // anyone who logged in with 'changeme' could have created a personal key,
        // and API key authentication checks the key, not its owner (found in review).
        final int apiKeys;
        try (PreparedStatement stmt = connection.prepareStatement("""
                UPDATE api_keys SET revoked_at = now() WHERE owner_user_id = ? AND revoked_at IS NULL
                """)) {
            stmt.setObject(1, userId);
            apiKeys = stmt.executeUpdate();
        }
        log.warn("Archived the seeded admin {} in tenant {} (id={}): its password was still the "
                        + "published default 'changeme'; {} unused token(s) invalidated, {} API key(s) "
                        + "revoked. Tenants and their first admins are now provisioned by a platform "
                        + "operator (backlog #0-80, docs/tenant-provisioning.md)",
                email, tenantId, userId, tokens, apiKeys);
        return Result.ARCHIVED;
    }

    private static String env(String name, String defaultValue) {
        final String value = System.getenv(name);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}
