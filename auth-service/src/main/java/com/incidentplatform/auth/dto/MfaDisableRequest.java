package com.incidentplatform.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Request for POST /auth/mfa/disable. Requires both factors for security. */
public record MfaDisableRequest(
        @NotBlank(message = "password must not be blank")
        String password,

        @NotBlank(message = "totpCode must not be blank")
        @Pattern(regexp = "^[0-9]{6}$", message = "totpCode must be exactly 6 digits")
        String totpCode
) {}