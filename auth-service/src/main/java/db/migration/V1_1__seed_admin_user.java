package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Formerly seeded the bootstrap admin; creates nothing since backlog #0-80.
 *
 * <h2>Fixed (backlog #0-80): no admin with a known password</h2>
 * This migration created a {@code ROLE_ADMIN} user in tenant {@code default} on
 * every new database: {@code ADMIN_EMAIL} / {@code ADMIN_PASSWORD} /
 * {@code ADMIN_TENANT_ID}, defaulting to {@code admin@incidentplatform.com} /
 * {@code changeme} / {@code default}. No deployment set {@code ADMIN_PASSWORD}, so
 * every deployment had a known admin login, reachable through the Ingress. It
 * only logged a warning.
 *
 * <p>A customer tenant and its first admin are now created by a platform
 * operator ({@code POST /api/v1/platform/tenants}, {@code TenantProvisioningService},
 * docs/tenant-provisioning.md); the admin is invited by email and sets their own
 * password, as the operator tenant's admin is (backlog #0-16, #0-49). A database that ran
 * the old version of this migration still has the account; {@code V20} archives
 * it where its password is still {@code changeme}.
 *
 * <h2>Why the class stays</h2>
 * Flyway records version 1.1 as applied on every existing database and refuses
 * to start if a recorded migration can no longer be resolved, so the class is
 * kept and only its body changed. A Java migration has no checksum, and Flyway
 * never runs an applied version again, so the change affects new databases only.
 */
public class V1_1__seed_admin_user extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V1_1__seed_admin_user.class);

    @Override
    public void migrate(Context context) {
        log.info("No bootstrap admin seeded (backlog #0-80): a platform operator provisions "
                + "each customer tenant and invites its first admin (POST /api/v1/platform/tenants, "
                + "docs/tenant-provisioning.md)");
    }
}
