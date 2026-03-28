package com.incidentplatform.ingestion.normalizer;

import com.fasterxml.jackson.databind.JsonNode;

public interface AlertNormalizer {

    NormalizationResult normalize(JsonNode rawPayload, String tenantId);

    String getSourceName();
}