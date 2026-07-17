package com.incidentplatform.auth.dto;

import com.incidentplatform.auth.domain.ApiKey;
import com.incidentplatform.auth.domain.ApiKeyType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Safe API key representation for list/get endpoints.
 * Never includes the raw key or hash — only metadata.
 */
public record ApiKeyDto(
        UUID id,
        String name,
        ApiKeyType keyType,

        /** First 8 chars for UI identification. Example: "ipl_abc1" */
        String keyPrefix,
        List<String> scopes,
        String ownerEmail,    // null for TENANT keys
        Instant lastUsedAt,
        Instant expiresAt,
        Instant createdAt,
        boolean active
) {
    public static ApiKeyDto from(ApiKey key) {
        return new ApiKeyDto(
                key.getId(),
                key.getName(),
                key.getKeyType(),
                key.getKeyPrefix(),
                key.getScopes(),
                key.getOwnerUser() != null ? key.getOwnerUser().getEmail() : null,
                key.getLastUsedAt(),
                key.getExpiresAt(),
                key.getCreatedAt(),
                key.isActive()
        );
    }
}