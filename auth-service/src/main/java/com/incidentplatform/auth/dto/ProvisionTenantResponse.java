package com.incidentplatform.auth.dto;

import java.util.UUID;

/**
 * The result of provisioning a tenant (backlog #0-80): the tenant and the
 * first admin, created without a password and invited by email.
 */
public record ProvisionTenantResponse(
        String tenantId,
        String displayName,
        UUID adminUserId,
        String adminEmail
) {}
