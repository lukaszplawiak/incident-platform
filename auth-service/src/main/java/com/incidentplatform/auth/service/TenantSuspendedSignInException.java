package com.incidentplatform.auth.service;

import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.security.TenantAccess;
import org.springframework.http.HttpStatus;

import java.util.Objects;
import java.util.UUID;

/**
 * A sign-in refused because its tenant is suspended (backlog #0-82): 403
 * {@code TENANT_SUSPENDED}, or {@code TENANT_READ_ONLY} for an invite into a
 * read-only tenant. Its own type, not a plain {@link BusinessException}, so
 * {@code SignInRefusalHandler} can audit and count it (step 2, found in the
 * review of step 1: such a refusal left no trace) once the refused request's
 * transaction has rolled back — an audit event written inside it would be
 * rolled back with it (backlog #0-84).
 */
public class TenantSuspendedSignInException extends BusinessException {

    private final String tenantId;
    private final UUID userId;
    private final SignInFlow flow;
    private final TenantAccess access;

    public TenantSuspendedSignInException(String tenantId, UUID userId, SignInFlow flow, TenantAccess access) {
        super(access == TenantAccess.READ_ONLY ? ErrorCodes.TENANT_READ_ONLY : ErrorCodes.TENANT_SUSPENDED,
                access == TenantAccess.READ_ONLY
                        ? "This organisation's account is suspended to read-only: changes are refused until it "
                                + "is resumed."
                        : "This organisation's account is suspended. Contact the platform operator.",
                HttpStatus.FORBIDDEN);
        if (access == TenantAccess.FULL) {
            throw new IllegalArgumentException("a sign-in is not refused for a tenant with full access");
        }
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.flow = Objects.requireNonNull(flow, "flow");
        this.access = Objects.requireNonNull(access, "access");
    }

    public String tenantId() {
        return tenantId;
    }

    public UUID userId() {
        return userId;
    }

    public SignInFlow flow() {
        return flow;
    }

    public TenantAccess access() {
        return access;
    }
}
