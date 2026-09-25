package com.incidentplatform.shared.security;

import java.util.Locale;
import java.util.Set;

/**
 * Tenant ids that no user or configuration may create (backlog #0-16).
 *
 * <p>Tenant ids are free-form strings (there is no tenant registry); a tenant
 * exists once the first admin is seeded or invited into it. That makes a name
 * with platform meaning collide-able: before this class, a real customer could
 * have been called {@code system}, the tenant the old Alertmanager token wrote
 * into. The names here are refused wherever an id is <em>chosen</em> (the admin
 * seed, the dev token endpoint); choosing an existing tenant at login is not
 * creation and stays allowed.
 *
 * <h2>Reserved is not privileged</h2>
 * A reserved tenant is an ordinary, isolated tenant. It has no cross-tenant
 * access, and nothing may branch on {@link #isReserved} for an authorization
 * decision: the operator tenant sees its own incidents and nothing else.
 */
public final class ReservedTenants {

    /**
     * The platform operator's own tenant. The platform's Alertmanager posts
     * non-critical platform alerts into it with an Integration API key of this
     * tenant, as incidents with history and postmortems; critical ones also go
     * out of band (docker/alertmanager.yml). Its first admin is bootstrapped by
     * invite in auth-service ({@code OperatorTenantBootstrap}).
     */
    public static final String PLATFORM_OPERATOR = "platform-operator";

    /**
     * The tenant id the removed Alertmanager service token wrote into. Kept
     * reserved so that incidents already stored under it can never become
     * visible to a newly created tenant of the same name.
     */
    public static final String LEGACY_SYSTEM = "system";

    private static final Set<String> RESERVED = Set.of(PLATFORM_OPERATOR, LEGACY_SYSTEM);

    private ReservedTenants() {
    }

    /** Case-insensitive, so {@code Platform-Operator} cannot be created either. */
    public static boolean isReserved(String tenantId) {
        return tenantId != null
                && RESERVED.contains(tenantId.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * @throws IllegalArgumentException if {@code tenantId} is reserved; the
     *         message names the reserved id, which is a constant, not input
     */
    public static void requireNotReserved(String tenantId) {
        if (isReserved(tenantId)) {
            throw new IllegalArgumentException("Tenant id '" + tenantId.trim()
                    + "' is reserved by the platform and cannot be created");
        }
    }
}
