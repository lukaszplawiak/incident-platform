package com.incidentplatform.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Request for POST /auth/mfa/enable. */
public record MfaEnableRequest(
        @NotBlank(message = "totpCode must not be blank")
        @Pattern(regexp = "^[0-9]{6}$", message = "totpCode must be exactly 6 digits")
        String totpCode
) {}