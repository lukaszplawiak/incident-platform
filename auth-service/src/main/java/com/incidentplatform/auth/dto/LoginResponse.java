package com.incidentplatform.auth.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response from {@code POST /api/v1/auth/login}.
 *
 * <h2>Three scenarios</h2>
 * <ol>
 *   <li><b>MFA not required</b> — {@link #mfaRequired} and
 *       {@link #mfaSetupRequired} both false, {@link #accessToken} and
 *       {@link #refreshToken} are populated. Client can use the access
 *       token immediately.</li>
 *   <li><b>MFA required (already configured)</b> — {@link #mfaRequired} is
 *       true, {@link #accessToken} and {@link #refreshToken} are null,
 *       {@link #mfaToken} is populated. Client must call
 *       {@code POST /auth/mfa/verify} with the mfaToken + TOTP code.</li>
 *   <li><b>MFA required by tenant, not yet configured</b> —
 *       {@link #mfaSetupRequired} is true, {@link #mfaSetupToken} is
 *       populated, everything else is null. Password was verified
 *       correctly, but the user has no MFA set up and the tenant requires
 *       it (see TenantSettingsController). The user has no access token —
 *       login has not completed. Client must call
 *       {@code POST /auth/mfa/setup-required} (QR/secret) then
 *       {@code POST /auth/mfa/enable-required} (confirm code) using
 *       mfaSetupToken instead of a Bearer token. Enable-required completes
 *       login and returns real access/refresh tokens.</li>
 * </ol>
 *
 * <h2>Two-token model (when MFA not required)</h2>
 * <ul>
 *   <li>{@link #accessToken} — short-lived JWT (default PT15M) for API calls.</li>
 *   <li>{@link #refreshToken} — long-lived opaque token (default P30D).</li>
 * </ul>
 */
public record LoginResponse(
        String accessToken,
        String refreshToken,
        UUID userId,
        String tenantId,
        String email,
        List<String> roles,
        Instant accessExpiresAt,
        Instant refreshExpiresAt,

        /**
         * True when the user has MFA already enabled. When true:
         * {@link #accessToken} and {@link #refreshToken} are null. The
         * client must call POST /auth/mfa/verify with {@link #mfaToken}.
         */
        boolean mfaRequired,

        /**
         * Short-lived MFA session token (5 minutes).
         * Non-null only when {@link #mfaRequired} is true.
         */
        String mfaToken,

        /** Expiry time of the MFA session token. Null when mfaRequired=false. */
        Instant mfaExpiresAt,

        /**
         * True when the tenant requires MFA but this user has none
         * configured. When true: {@link #accessToken}, {@link #refreshToken},
         * {@link #mfaRequired} and {@link #mfaToken} are all null/false —
         * this is a distinct third state, not a variant of mfaRequired.
         * The client must complete setup via {@link #mfaSetupToken}.
         */
        boolean mfaSetupRequired,

        /**
         * Short-lived MFA setup-required token (10 minutes).
         * Non-null only when {@link #mfaSetupRequired} is true.
         */
        String mfaSetupToken,

        /** Expiry time of the MFA setup-required token. Null otherwise. */
        Instant mfaSetupExpiresAt
) {

    /**
     * Factory for successful single-factor login (MFA not required),
     * and for completing login after MFA verification/setup.
     */
    public static LoginResponse success(String accessToken, String refreshToken,
                                        UUID userId, String tenantId, String email,
                                        List<String> roles, Instant accessExpiresAt,
                                        Instant refreshExpiresAt) {
        return new LoginResponse(
                accessToken, refreshToken, userId, tenantId,
                email, roles, accessExpiresAt, refreshExpiresAt,
                false, null, null,
                false, null, null);
    }

    /**
     * Factory for MFA-required login — user already has MFA configured,
     * access token not yet issued.
     */
    public static LoginResponse mfaRequired(String mfaToken, Instant mfaExpiresAt) {
        return new LoginResponse(
                null, null, null, null, null, null,
                null, null,
                true, mfaToken, mfaExpiresAt,
                false, null, null);
    }

    /**
     * Factory for MFA-setup-required login — tenant requires MFA, user has
     * none configured, password was correct but login cannot complete
     * until setup finishes via POST /mfa/enable-required.
     */
    public static LoginResponse mfaSetupRequired(String mfaSetupToken, Instant mfaSetupExpiresAt) {
        return new LoginResponse(
                null, null, null, null, null, null,
                null, null,
                false, null, null,
                true, mfaSetupToken, mfaSetupExpiresAt);
    }
}