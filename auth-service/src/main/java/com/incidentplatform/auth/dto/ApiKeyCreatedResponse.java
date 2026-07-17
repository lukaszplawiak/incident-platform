package com.incidentplatform.auth.dto;

import com.incidentplatform.auth.domain.ApiKey;
import com.incidentplatform.auth.domain.ApiKeyType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response from {@code POST /api/v1/api-keys}.
 *
 * <p>Contains the raw API key in {@link #rawKey}. This is the ONLY time
 * the raw key is returned — it cannot be retrieved again. The client must
 * store it securely immediately.
 */
public record ApiKeyCreatedResponse(
        UUID id,
        String name,
        ApiKeyType keyType,
        List<String> scopes,

        /**
         * The full raw API key — shown ONCE, never stored.
         * Format: {@code ipl_<prefix8>.<random32>}
         */
        String rawKey,

        Instant expiresAt,
        Instant createdAt,

        String message
) {
    public static ApiKeyCreatedResponse from(ApiKey key, String rawKey) {
        return new ApiKeyCreatedResponse(
                key.getId(),
                key.getName(),
                key.getKeyType(),
                key.getScopes(),
                rawKey,
                key.getExpiresAt(),
                key.getCreatedAt(),
                "Store this key securely — it will not be shown again."
        );
    }
}