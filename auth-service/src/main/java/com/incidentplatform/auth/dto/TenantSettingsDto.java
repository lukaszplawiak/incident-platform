package com.incidentplatform.auth.dto;

/**
 * Response for GET/POST /api/v1/tenants/settings.
 *
 * @param activeAdmins backlog #0-90: the tenant's active admins who can log in
 *                     (active, invite accepted)
 * @param singleAdmin  backlog #0-90: true while that is one (or none). A
 *                     warning for the admins: with a second admin, either can
 *                     reset the other's MFA (POST /api/v1/users/{id}/mfa-reset);
 *                     with one, a lost factor needs the platform operator's
 *                     recovery, which waits 72 h after telling the account.
 */
public record TenantSettingsDto(
        String tenantId,
        boolean mfaRequired,
        long activeAdmins,
        boolean singleAdmin
) {

    public static TenantSettingsDto of(String tenantId, boolean mfaRequired, long activeAdmins) {
        return new TenantSettingsDto(tenantId, mfaRequired, activeAdmins, activeAdmins <= 1);
    }
}
