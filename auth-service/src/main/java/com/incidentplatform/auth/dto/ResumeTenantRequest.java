package com.incidentplatform.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A platform operator resumes a suspended tenant (backlog #0-82,
 * POST /api/v1/platform/tenants/{tenantId}/resume).
 *
 * @param note why, in a line; kept in the operator tenant's audit trail
 */
public record ResumeTenantRequest(
        @NotBlank(message = "note is required")
        @Size(max = 500, message = "note must be at most 500 characters") String note
) {}
