package com.incidentplatform.auth.dto;

/** Request for POST /api/v1/tenants/settings. */
public record UpdateTenantSettingsRequest(
        boolean mfaRequired
) {}