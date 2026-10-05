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
}
