package com.incidentplatform.auth.dto;

/** Response for GET/POST /api/v1/tenants/settings. */
public record TenantSettingsDto(
        String tenantId,
        boolean mfaRequired
) {}