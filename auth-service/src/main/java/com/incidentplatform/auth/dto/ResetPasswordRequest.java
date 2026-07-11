package com.incidentplatform.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/reset-password}.
 */
public record ResetPasswordRequest(

        @NotBlank(message = "token must not be blank")
        String token,

        @NotBlank(message = "newPassword must not be blank")
        @Size(min = 8, message = "newPassword must be at least 8 characters")
        String newPassword

) {}