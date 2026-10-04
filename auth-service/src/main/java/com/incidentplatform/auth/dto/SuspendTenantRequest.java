package com.incidentplatform.auth.dto;

import com.incidentplatform.auth.domain.SuspensionMode;
import com.incidentplatform.auth.domain.SuspensionReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A platform operator suspends a tenant, or changes how it is suspended
 * (backlog #0-82, POST /api/v1/platform/tenants/{tenantId}/suspend).
 *
 * @param mode   FULL (nothing works) or READ_ONLY (reads go on, writes refused but account security)
 * @param reason SECURITY, BILLING, TERMS or OTHER
 * @param note   why, in a line; kept in the operator tenant's audit trail and shown to operators
 */
public record SuspendTenantRequest(
        @NotNull(message = "mode is required") SuspensionMode mode,
        @NotNull(message = "reason is required") SuspensionReason reason,
        @NotBlank(message = "note is required")
        @Size(max = 500, message = "note must be at most 500 characters") String note
) {}
