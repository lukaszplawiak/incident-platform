package com.incidentplatform.auth.domain;

/**
 * A tenant's life-cycle status (backlog #0-82, V30). Changed only by a platform
 * operator, through conditional UPDATEs guarded by the current status
 * ({@code TenantRepository}).
 */
public enum TenantStatus {
    /** Working normally. */
    ACTIVE,
    /** Reversibly stopped, in full or read-only ({@link SuspensionMode}); its data stays. */
    SUSPENDED,
    /** Reserved for offboarding (its own backlog item): data being exported and removed. */
    OFFBOARDING,
    /** Reserved for offboarding: a tombstone, the id stays taken, the data is gone. */
    OFFBOARDED
}
