package com.incidentplatform.shared.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public final class TenantContext {

    private static final Logger log = LoggerFactory.getLogger(TenantContext.class);

    public static final String MDC_TENANT_KEY = "tenantId";

    private static final InheritableThreadLocal<String> TENANT_ID =
            new InheritableThreadLocal<>();

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

    public static String get() {
        String tenantId = TENANT_ID.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "TenantContext is not set for current thread. " +
                            "Ensure JwtAuthFilter is configured and request has valid JWT token."
            );
        }
        return tenantId;
    }

    public static String getRequired() {
        final String tenantId = TENANT_ID.get();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException(
                    "TenantContext is empty — JWT filter should have set it");
        }
        return tenantId;
    }

    public static String getOrNull() {
        return TENANT_ID.get();
    }

    public static boolean isSet() {
        return TENANT_ID.get() != null;
    }

    public static void clear() {
        String tenantId = TENANT_ID.get();
        TENANT_ID.remove();
        MDC.remove(MDC_TENANT_KEY);
        log.debug("TenantContext cleared for tenant: {}", tenantId);
    }
}