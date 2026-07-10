package com.incidentplatform.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/auth/refresh}.
 */
public record RefreshRequest(

        @NotBlank(message = "refreshToken must not be blank")
        String refreshToken

) {}