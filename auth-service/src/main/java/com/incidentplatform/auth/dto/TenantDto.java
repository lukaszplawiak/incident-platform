package com.incidentplatform.auth.dto;

import com.incidentplatform.auth.domain.SuspensionMode;
import com.incidentplatform.auth.domain.SuspensionReason;
import com.incidentplatform.auth.domain.Tenant;
import com.incidentplatform.auth.domain.TenantStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * A tenant as the platform operator sees it (backlog #0-80): metadata only, no
 * data of the tenant.
 *
 * @param firstAdminEmail   null for tenants that existed before tenants were
 *                          recorded (backfilled)
 * @param adminActive       whether the tenant has an active admin who has set a
 *                          password, i.e. whether its first admin has accepted
 * @param createdBy         the operator who provisioned it; null if backfilled
 * @param status            backlog #0-82: ACTIVE, SUSPENDED, ...
 * @param suspensionMode    FULL or READ_ONLY while suspended, else null
 * @param suspensionReason  why, while suspended, else null
 * @param suspensionNote    the operator's note, while suspended, else null
 * @param suspendedAt       since when, while suspended, else null
 * @param suspendedBy       the operator who suspended it (or last changed how), else null
 */
public record TenantDto(
        String tenantId,
        String displayName,
        String firstAdminEmail,
        boolean adminActive,
        Instant createdAt,
        UUID createdBy,
        TenantStatus status,
        SuspensionMode suspensionMode,
        SuspensionReason suspensionReason,
        String suspensionNote,
        Instant suspendedAt,
        UUID suspendedBy
) {

    public static TenantDto from(Tenant tenant, boolean adminActive) {
        return new TenantDto(tenant.getTenantId(), tenant.getDisplayName(), tenant.getFirstAdminEmail(),
                adminActive, tenant.getCreatedAt(), tenant.getCreatedBy(), tenant.getStatus(),
                tenant.getSuspensionMode(), tenant.getSuspensionReason(), tenant.getSuspensionNote(),
                tenant.getSuspendedAt(), tenant.getSuspendedBy());
    }
}
