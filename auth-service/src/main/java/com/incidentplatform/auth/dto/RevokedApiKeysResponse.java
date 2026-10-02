package com.incidentplatform.auth.dto;

import java.util.List;
import java.util.UUID;

/**
 * What a bulk revocation revoked (backlog #0-89): the keys, and the
 * integrations among them (an integration goes with its key).
 */
public record RevokedApiKeysResponse(
        List<UUID> revokedKeyIds,
        List<UUID> revokedIntegrationIds
) {

    public static RevokedApiKeysResponse none() {
        return new RevokedApiKeysResponse(List.of(), List.of());
    }

    public int count() {
        return revokedKeyIds.size();
    }
}
