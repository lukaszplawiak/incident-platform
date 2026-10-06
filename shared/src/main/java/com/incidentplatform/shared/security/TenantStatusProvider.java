package com.incidentplatform.shared.security;

import java.util.Optional;

/**
 * Tells {@link TenantStatusFilter} what a tenant may do (backlog #0-82).
 *
 * <p>The same arrangement as {@link TokenRevocationChecker}: the interface lives
 * in {@code shared}, so the filter can depend on it; auth-service, which owns
 * the {@code tenants} table, implements it from its database; every other
 * service that sets {@code auth-service.base-url} asks auth-service through
 * {@link AuthServiceTenantStatusProvider} (step 2 of #0-82); a context without
 * that URL (test slices) gets {@link SharedSecurityAutoConfiguration}'s
 * default, which answers {@link TenantAccess#FULL} for every tenant.
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

    /**
     * auth-service's own answer about the tenant, with the time its suspension
     * began, the last answer this service had however old (backlog #0-82, step
     * 2b), or empty when it never had one: the FULL {@link #accessOf} gives a
     * tenant it could not ask about is fail-open, not an answer. For a caller
     * that changes durable state on the answer, such as {@code
     * PausedTenantsSync}, which must not resume a tenant it paused just because
     * auth-service is down when this service restarts, and measures the pause
     * from the suspension's own time. May wait like {@link #accessOf}. The
     * default always has an answer, without a time; a provider that knows it
     * (auth-service's own) overrides it.
     *
     * @param tenantId a valid tenant id
     */
    default Optional<TenantAccessState> confirmedStateOf(String tenantId) {
        return Optional.of(new TenantAccessState(accessOf(tenantId), null));
    }
}
