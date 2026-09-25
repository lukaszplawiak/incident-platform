package com.incidentplatform.ingestion.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Timeouts of ingestion-service's outbound HTTP calls — its first, API key
 * introspection into auth-service (backlog #0-16). Short on purpose: the call
 * sits on the request path of an alert, and on a timeout the sender is told to
 * retry (503) rather than kept waiting.
 */
@ConfigurationProperties(prefix = "ingestion.client")
@Validated
public record IngestionClientProperties(
        @Positive(message = "ingestion.client.connect-timeout-seconds must be positive")
        int connectTimeoutSeconds,

        @Positive(message = "ingestion.client.read-timeout-seconds must be positive")
        int readTimeoutSeconds
) {
}
