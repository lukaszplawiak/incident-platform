package com.incidentplatform.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The account cancels an operator's MFA recovery request with the token from
 * its notice (backlog #0-90, POST /api/v1/auth/mfa-recovery/cancel).
 */
public record CancelMfaRecoveryRequest(
        @NotBlank(message = "token is required")
        @Size(max = 200, message = "token is too long")
        String token
) {}
