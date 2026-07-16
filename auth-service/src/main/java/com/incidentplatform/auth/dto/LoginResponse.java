package com.incidentplatform.auth.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response from {@code POST /api/v1/auth/login}.
 *
 * <h2>Two scenarios</h2>
 * <ol>
 *   <li><b>MFA not required</b> — {@link #mfaRequired} is false,
 *       {@link #accessToken} and {@link #refreshToken} are populated,
 *       {@link #mfaToken} is null. Client can use the access token immediately.</li>
 *   <li><b>MFA required</b> — {@link #mfaRequired} is true,
 *       {@link #accessToken} and {@link #refreshToken} are null,
 *       {@link #mfaToken} is populated. Client must call
 *       {@code POST /auth/mfa/verify} with the mfaToken + TOTP code.</li>
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
         * True when the user has MFA enabled or the tenant requires MFA.
         * When true: {@link #accessToken} and {@link #refreshToken} are null.
         * The client must call POST /auth/mfa/verify with {@link #mfaToken}.
         */
        boolean mfaRequired,

        /**
         * Short-lived MFA session token (5 minutes).
         * Non-null only when {@link #mfaRequired} is true.
         */
        String mfaToken,

        /** Expiry time of the MFA session token. Null when mfaRequired=false. */
        Instant mfaExpiresAt
) {

    /**
     * Factory for successful single-factor login (MFA not required).
     */
    public static LoginResponse success(String accessToken, String refreshToken,
                                        UUID userId, String tenantId, String email,
                                        List<String> roles, Instant accessExpiresAt,
                                        Instant refreshExpiresAt) {
        return new LoginResponse(
                accessToken, refreshToken, userId, tenantId,
                email, roles, accessExpiresAt, refreshExpiresAt,
                false, null, null);
    }

    /**
     * Factory for MFA-required login — access token not yet issued.
     */
    public static LoginResponse mfaRequired(String mfaToken, Instant mfaExpiresAt) {
        return new LoginResponse(
                null, null, null, null, null, null,
                null, null,
                true, mfaToken, mfaExpiresAt);
    }
}