package com.incidentplatform.ingestion.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public interface AlertNormalizer {

    /**
     * Normalizes a raw alert payload into {@link NormalizationResult}.
     *
     * @param teamId resolved from the Integration that authenticated the request.
     *               Null for JWT-authenticated requests or integrations without teams.
     *               Must be propagated to every {@link com.incidentplatform.shared.dto.UnifiedAlertDto}
     *               so incident-service can set {@code Incident.team_id}.
     */
    NormalizationResult normalize(JsonNode rawPayload, String tenantId, UUID teamId);

    String getSourceName();
}