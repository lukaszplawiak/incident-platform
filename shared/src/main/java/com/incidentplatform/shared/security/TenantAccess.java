package com.incidentplatform.shared.security;

/**
 * What a tenant's users and API keys may do, as far as the tenant's own status
 * decides it (backlog #0-82). The reasons behind a status (billing, a taken-over
 * account, terms) are auth-service's business; every other service needs only
 * this.
 */
public enum TenantAccess {
    /** The tenant is active: nothing is refused on its account. */
    FULL,
    /**
     * Suspended read-only (for example over billing): reads go on, writes are
     * refused, except the account-security ones a service lists
     * ({@code tenant-status.read-only.allowed-writes}).
     */
    READ_ONLY,
    /** Suspended in full (for example a taken-over tenant): every request is refused. */
    NONE
}
