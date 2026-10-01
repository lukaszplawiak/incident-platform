package com.incidentplatform.auth.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A tenant as the platform operator sees it (backlog #0-80): metadata only, no
 * data of the tenant.
 *
 * @param firstAdminEmail null for tenants that existed before tenants were
 *                        recorded (backfilled)
 * @param adminActive     whether the tenant has an active admin who has set a
 *                        password, i.e. whether its first admin has accepted
 * @param createdBy       the operator who provisioned it; null if backfilled
 */
public record TenantDto(
        String tenantId,
        String displayName,
        String firstAdminEmail,
        boolean adminActive,
        Instant createdAt,
        UUID createdBy
) {}
