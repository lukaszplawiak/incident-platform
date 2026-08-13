package com.incidentplatform.ingestion.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the ingestion pipeline.
 *
 * <p>Originally replaced one {@code @Value} injection in
 * {@link com.incidentplatform.ingestion.normalizer.PrometheusNormalizer}:
 * {@code ingestion.prometheus.max-batch-size}. Now also holds
 * {@code ingestion.max-payload-bytes}, used by
 * {@link PayloadSizeLimitFilter}.
 *
 * <h2>YAML configuration</h2>
 * <pre>{@code
 * ingestion:
 *   max-payload-bytes: ${INGESTION_MAX_PAYLOAD_BYTES:1048576}
 *   prometheus:
 *     max-batch-size: ${INGESTION_PROMETHEUS_MAX_BATCH_SIZE:500}
 * }</pre>
 */
@ConfigurationProperties(prefix = "ingestion")
@Validated
public record IngestionProperties(

        @NotNull @Valid
        Prometheus prometheus,

        /**
         * Maximum accepted request body size, in bytes, for
         * {@code POST /api/v1/alerts/{source}}. Enforced by
         * {@code PayloadSizeLimitFilter} — a servlet filter running before
         * Spring MVC's JSON deserialization, so an oversized payload is
         * rejected without ever being fully parsed into memory.
         *
         * <p>Fixed: previously enforced only inside
         * {@code AlertIngestionController} by checking
         * {@code rawPayload.toString().length()} — but {@code @RequestBody
         * JsonNode} means Spring has already fully read and parsed the
         * entire request body into a JSON tree before the controller
         * method even starts running, so that check rejected the request
         * only after the expensive, memory-consuming parse had already
         * happened. See {@code PayloadSizeLimitFilter}'s Javadoc for the
         * full account. Default: 1048576 (1 MiB).
         */
        @Positive(message = "ingestion.max-payload-bytes must be positive")
        int maxPayloadBytes

) {

    public record Prometheus(

            /**
             * Maximum number of alerts processed per Kafka message.
             * Limits memory usage when AlertManager sends large batches.
             * Default: 500.
             */
            @Positive(message = "ingestion.prometheus.max-batch-size must be positive")
            int maxBatchSize

    ) {}
}