package com.incidentplatform.ingestion_normalizer;

import com.fasterxml.jackson.databind.JsonNode;

public interface AlertNormalizer {

    NormalizationResult normalize(JsonNode rawPayload, String tenantId);

    String getSourceName();
}