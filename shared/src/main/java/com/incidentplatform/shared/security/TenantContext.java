package com.incidentplatform.shared.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Thread-local holder for the current request's tenant ID.
 *
 * <p>Set by {@link JwtAuthFilter} after validating the JWT for every
 * authenticated request, and cleared at the end of request processing
 * (or at the end of Kafka record processing — see the various
 * {@code IncidentEventConsumer} implementations).
 */
public final class TenantContext {

    private static final Logger log =
            LoggerFactory.getLogger(TenantContext.class);

    public static final String MDC_TENANT_KEY = "tenantId";

    /**
     * {@link jakarta.servlet.http.HttpServletRequest} attribute key under
     * which the resolved tenant ID is also stored, in addition to this
     * ThreadLocal.
     *
     * <p>{@code TenantContext} (this ThreadLocal) is cleared before
     * {@code ServerHttpObservationFilter} finishes recording the
     * {@code http.server.requests} observation — that filter wraps the
     * entire Spring Security chain (including {@code JwtAuthFilter}), so its
     * {@code stop()} runs after {@code JwtAuthFilter}'s {@code finally} block
     * has already cleared this ThreadLocal and MDC. The request attribute
     * survives for the full request lifecycle regardless of ThreadLocal
     * cleanup, so it's the channel
     * {@code TenantServerRequestObservationConvention} reads from instead.
     *
     * <p>Declared here rather than on the observation convention class
     * itself so neither {@code JwtAuthFilter} nor
     * {@code TenantServerRequestObservationConvention} depends on the
     * other directly — both reference this shared constant, the same
     * pattern used for {@link SharedSecurityAutoConfiguration#PUBLIC_PATHS}.
     */
    public static final String REQUEST_ATTRIBUTE_TENANT_ID = "tenantId";

    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();

    private TenantContext() {
        throw new UnsupportedOperationException(
                "TenantContext is a utility class and cannot be instantiated"
        );
    }

    public static void set(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("Attempted to set null or blank tenantId in TenantContext");
            return;
        }
        TENANT_ID.set(tenantId);
        MDC.put(MDC_TENANT_KEY, tenantId);
        log.debug("TenantContext set for tenant: {}", tenantId);
    }

    /**
     * Returns the tenant ID for the current thread.
     *
     * <p>{@code set()} rejects {@code null}/blank values, so a non-null
     * return from this method is always a valid, non-blank tenant ID.
     *
     * @throws IllegalStateException if no tenant ID has been set for the
     *         current thread — indicates a programming error (calling code
     *         outside an authenticated request or Kafka record processing
     *         context). Use {@link #getOrNull()} or {@link #isSet()} for
     *         call sites where an unset tenant is an expected, handled case.
     */
    public static String get() {
        final String tenantId = TENANT_ID.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "TenantContext is not set for current thread. " +
                            "Ensure JwtAuthFilter is configured and request has valid JWT token."
            );
        }
        return tenantId;
    }

    /**
     * Returns the tenant ID for the current thread, or {@code null} if unset.
     * Use when an unset tenant is a valid, expected state that the caller
     * handles explicitly (e.g. poison-pill detection in Kafka consumers).
     */
    public static String getOrNull() {
        return TENANT_ID.get();
    }

    public static boolean isSet() {
        return TENANT_ID.get() != null;
    }

    public static void clear() {
        final String tenantId = TENANT_ID.get();
        TENANT_ID.remove();
        MDC.remove(MDC_TENANT_KEY);
        log.debug("TenantContext cleared for tenant: {}", tenantId);
    }
}