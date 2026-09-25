package com.incidentplatform.auth.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Body of {@code POST /api/v1/internal/api-keys/introspect} (backlog #0-16).
 *
 * @param keyHash lowercase hex SHA-256 of the raw key
 *                ({@code ApiKeyHashing.sha256Hex}). The raw key never leaves
 *                the service that received it.
 */
public record ApiKeyIntrospectionRequest(
        @NotNull(message = "keyHash is required")
        @Pattern(regexp = "^[0-9a-f]{64}$",
                message = "keyHash must be a lowercase hex SHA-256 digest")
        String keyHash
) { }
