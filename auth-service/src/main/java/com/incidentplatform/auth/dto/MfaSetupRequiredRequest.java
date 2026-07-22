package com.incidentplatform.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Request for POST /auth/mfa/setup-required — tenant-required-MFA login flow. */
public record MfaSetupRequiredRequest(
        @NotBlank(message = "mfaSetupToken must not be blank")
        String mfaSetupToken
) {}