package com.incidentplatform.ingestion.apikey;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An active Integration API key as auth-service describes it (backlog #0-16):
 * the tenant and team an alert sent with it belongs to, and its scopes.
 *
 * @param teamId    team of the key's Integration; {@code null} if it has none
 * @param expiresAt {@code null} for a key that never expires; an answer is
 *                  never cached beyond it
 */
public record IntrospectedApiKey(UUID keyId, String tenantId, UUID teamId,
                                 List<String> scopes, Instant expiresAt) {

    public IntrospectedApiKey {
        if (keyId == null || tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("keyId and tenantId are required");
        }
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }
}
