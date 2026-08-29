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

        /**
         * Fixed (backlog #72): documenting semantics that were previously
         * implicit and easy to misread. This counts firing alerts handed
         * off to {@code AlertKafkaProducer.publishFiring} — NOT confirmed
         * delivered to Kafka. The send itself is fire-and-forget/async by
         * deliberate design (see {@code AlertKafkaProducer}'s own Javadoc
         * for the full "occasional alert loss vs endpoint availability
         * under pressure" reasoning); this count increments synchronously,
         * immediately after the async send is initiated, before its
         * result is known. If Kafka is entirely down for a whole batch,
         * a caller reading this summary would still see
         * {@code processed: N} for all N alerts — delivery FAILURES
         * during a Kafka outage surface via
         * {@code AlertKafkaProducer}'s own counter/logs, not through this
         * field or anywhere else in this summary. Deliberately NOT
         * renamed to something like {@code accepted} to more precisely
         * signal this — {@code processed} is both the wire-format JSON
         * key (external API contract) and the Java record component name
         * referenced throughout this codebase's tests; a rename's blast
         * radius wasn't judged proportionate to a documentation-clarity
         * finding with no actual behavior change behind it.
         */
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