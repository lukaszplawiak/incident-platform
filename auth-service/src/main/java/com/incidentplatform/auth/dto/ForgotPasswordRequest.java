package com.incidentplatform.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/auth/forgot-password}.
 */
public record ForgotPasswordRequest(

        @NotBlank(message = "email must not be blank")
        @Email(message = "email must be a valid email address")
        String email

) {}