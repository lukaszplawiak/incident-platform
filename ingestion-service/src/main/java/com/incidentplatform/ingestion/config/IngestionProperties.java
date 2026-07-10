package com.incidentplatform.ingestion.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the ingestion pipeline.
 *
 * <p>Replaces one {@code @Value} injection in
 * {@link com.incidentplatform.ingestion.normalizer.PrometheusNormalizer}:
 * {@code ingestion.prometheus.max-batch-size}.
 *
 * <h2>YAML configuration</h2>
 * <pre>{@code
 * ingestion:
 *   prometheus:
 *     max-batch-size: ${INGESTION_PROMETHEUS_MAX_BATCH_SIZE:500}
 * }</pre>
 */
@ConfigurationProperties(prefix = "ingestion")
@Validated
public record IngestionProperties(

        @NotNull @Valid
        Prometheus prometheus

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