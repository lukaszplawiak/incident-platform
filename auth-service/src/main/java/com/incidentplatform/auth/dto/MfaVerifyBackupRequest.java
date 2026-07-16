package com.incidentplatform.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request for POST /auth/mfa/verify-backup. */
public record MfaVerifyBackupRequest(
        @NotBlank(message = "mfaToken must not be blank")
        String mfaToken,

        @NotBlank(message = "backupCode must not be blank")
        @Size(min = 8, max = 8, message = "backupCode must be 8 characters")
        String backupCode
) {}