package com.incidentplatform.shared.security;

/**
 * auth-service's answer on {@code GET /api/v1/internal/tenant-status} (backlog
 * #0-82): what the calling service's tenant may do. Only the
 * {@link TenantAccess} leaves auth-service, never the status behind it (mode,
 * reason, note): the other services need nothing more to enforce it.
 *
 * @param access what the tenant may do now; never {@code null} in an answer
 */
public record TenantStatusResponse(TenantAccess access) {
}
