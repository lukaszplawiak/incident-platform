package com.incidentplatform.shared.security;

/**
 * Tells {@link TenantStatusFilter} what a tenant may do (backlog #0-82).
 *
 * <p>The same arrangement as {@link TokenRevocationChecker}: the interface lives
 * in {@code shared}, so the filter can depend on it; auth-service, which owns
 * the {@code tenants} table, implements it from its database; every other
 * service gets {@link SharedSecurityAutoConfiguration}'s default, which answers
 * {@link TenantAccess#FULL} for every tenant, until it learns the status from
 * auth-service (the second step of #0-82: a cached pull, as notification-service
 * reads a tenant's Slack workspace).
 */
@FunctionalInterface
public interface TenantStatusProvider {

    /**
     * What the tenant may do now.
     *
     * @param tenantId a valid tenant id (the principal's, already checked)
     */
    TenantAccess accessOf(String tenantId);

    /**
     * What the tenant may do as far as known now, without waiting on anything
     * remote (backlog #0-82, step 2): for a caller that must not block, such as
     * a STOMP {@code CONNECT} on the message channel's threads or a sweep over
     * many tenants. A provider that asks another service answers from its cache
     * and refreshes it in the background; one that reads its own database (the
     * default) answers as {@link #accessOf}. Never stricter than
     * {@link #accessOf}, so the caller must enforce again where it matters.
     *
     * @param tenantId a valid tenant id (the principal's, already checked)
     */
    default TenantAccess knownAccessOf(String tenantId) {
        return accessOf(tenantId);
    }
}
