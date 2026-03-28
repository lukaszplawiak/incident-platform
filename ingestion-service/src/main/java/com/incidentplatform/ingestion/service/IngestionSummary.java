package com.incidentplatform.ingestion_service;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record IngestionSummary(

        @JsonProperty("received")
        int received,

        @JsonProperty("processed")
        int processed,

        @JsonProperty("duplicates")
        int duplicates,

        @JsonProperty("resolved")
        int resolved,

        @JsonProperty("deadLetter")
        int deadLetter,

        @JsonProperty("processedAt")
        Instant processedAt

) {
    public IngestionSummary {
        if (processedAt == null) processedAt = Instant.now();
    }

    public static IngestionSummary of(int received, int processed,
                                      int duplicates, int resolved,
                                      int deadLetter) {
        return new IngestionSummary(
                received, processed, duplicates, resolved, deadLetter,
                Instant.now()
        );
    }

    public boolean hasDeadLetterAlerts() {
        return deadLetter > 0;
    }

    public boolean isFullySuccessful() {
        return deadLetter == 0
                && (processed + duplicates + resolved) == received;
    }
}