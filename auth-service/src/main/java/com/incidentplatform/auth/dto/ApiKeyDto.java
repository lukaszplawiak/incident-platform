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

        /**
         * The 8 characters after "ipl_", for identification (e.g. "abcd1234" for
         * "ipl_abcd1234..."). Part of the secret, so the API_KEY_CREATED email shows
         * the key's id instead (backlog #0-89).
         */
        String keyPrefix,
        List<String> scopes,
        String ownerEmail,    // null for TENANT keys
        Instant lastUsedAt,
        Instant expiresAt,
        Instant createdAt,
        /** Who created the key (backlog #0-89); null for a tenant key made before V26. */
        UUID createdByUserId,
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
                key.getCreatedByUserId(),
                key.isActive()
        );
    }
}