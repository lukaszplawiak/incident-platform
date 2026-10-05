package com.incidentplatform.auth.domain;

import com.incidentplatform.shared.security.TenantAccess;

/** How a suspended tenant is stopped (backlog #0-82). */
public enum SuspensionMode {
    /** Nothing works for its users and keys: a taken-over tenant, a terms breach. */
    FULL(TenantAccess.NONE),
    /** Reads go on, writes are refused except account security: a billing hold. */
    READ_ONLY(TenantAccess.READ_ONLY);

    private final TenantAccess access;

    SuspensionMode(TenantAccess access) {
        this.access = access;
    }

    /** What the tenant's users and keys may do in this mode. */
    public TenantAccess access() {
        return access;
    }
}
