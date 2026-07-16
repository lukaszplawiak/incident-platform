package com.incidentplatform.auth.dto;

import java.util.List;

/**
 * Response from POST /auth/mfa/enable.
 * Backup codes shown exactly ONCE — user must save them securely.
 */
public record MfaEnableResponse(
        List<String> backupCodes,
        String message
) {
    public static MfaEnableResponse of(List<String> backupCodes) {
        return new MfaEnableResponse(
                backupCodes,
                "MFA enabled. Save these backup codes — they will not be shown again."
        );
    }
}