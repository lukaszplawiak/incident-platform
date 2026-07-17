package com.incidentplatform.auth.dto;

import com.incidentplatform.auth.domain.ApiKeyScope;
import com.incidentplatform.auth.domain.ApiKeyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Request body for {@code POST /api/v1/api-keys}.
 */
public record CreateApiKeyRequest(

        @NotBlank(message = "name must not be blank")
        @Size(min = 2, max = 100, message = "name must be between 2 and 100 characters")
        String name,

        @NotNull(message = "keyType must not be null")
        ApiKeyType keyType,

        @NotEmpty(message = "at least one scope must be specified")
        List<ApiKeyScope> scopes,

        /**
         * Optional expiry. Null = non-expiring key.
         * Must be in the future if provided.
         */
        Instant expiresAt

) {}