package com.incidentplatform.ingestion.service;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * <h2>Fixed: {@code received} silently reported the already-truncated count</h2>
 * See {@code NormalizationResult}'s Javadoc for the full account. In
 * short: when {@code PrometheusNormalizer} capped a batch at
 * {@code ingestion.prometheus.max-batch-size}, {@code received} used to
 * be computed from the already-capped result — a caller sending 1000
 * alerts against a 500 limit got back {@code received: 500} with no way
 * to tell that from "the payload only had 500 alerts". {@code received}
 * now reflects the true, pre-truncation count, and the new
 * {@link #truncated} field makes the "some alerts never got a chance to
 * be processed" situation explicit and easy to check, rather than making
 * a caller infer it from {@code processed + duplicates + resolved
 * received} arithmetic that {@link #isFullySuccessful} already does
 * internally.
 */
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

        @JsonProperty("truncated")
        boolean truncated,

        @JsonProperty("processedAt")
        Instant processedAt

) {
    public IngestionSummary {
        if (processedAt == null) processedAt = Instant.now();
    }

    public static IngestionSummary of(int received, int processed,
                                      int duplicates, int resolved,
                                      int deadLetter, boolean truncated) {
        return new IngestionSummary(
                received, processed, duplicates, resolved, deadLetter,
                truncated, Instant.now()
        );
    }

    public boolean hasDeadLetterAlerts() {
        return deadLetter > 0;
    }

    public boolean isFullySuccessful() {
        return deadLetter == 0
                && !truncated
                && (processed + duplicates + resolved) == received;
    }
}