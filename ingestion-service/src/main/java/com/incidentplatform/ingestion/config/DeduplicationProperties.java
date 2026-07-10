package com.incidentplatform.ingestion.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration for the alert deduplication subsystem.
 *
 * <p>Replaces one {@code @Value} injection in
 * {@link com.incidentplatform.ingestion.service.DeduplicationService}:
 * {@code deduplication.ttl-minutes} (was {@code int}, now {@link Duration}).
 *
 * <h2>YAML configuration</h2>
 * <pre>{@code
 * deduplication:
 *   ttl: ${DEDUPLICATION_TTL:PT5M}
 * }</pre>
 */
@ConfigurationProperties(prefix = "deduplication")
@Validated
public record DeduplicationProperties(

        /**
         * How long a deduplication key lives in Redis. Alerts arriving
         * within this window with the same fingerprint are rejected as
         * duplicates. Default: PT5M (5 minutes).
         */
        @NotNull(message = "deduplication.ttl must not be null")
        Duration ttl

) {}