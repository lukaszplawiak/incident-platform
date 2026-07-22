package com.incidentplatform.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Request for POST /auth/mfa/enable-required — tenant-required-MFA login flow. */
public record MfaEnableRequiredRequest(
        @NotBlank(message = "mfaSetupToken must not be blank")
        String mfaSetupToken,

        @NotBlank(message = "totpCode must not be blank")
        @Pattern(regexp = "^[0-9]{6}$", message = "totpCode must be exactly 6 digits")
        String totpCode
) {}