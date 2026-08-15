package com.incidentplatform.ingestion.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Configuration for the ingestion pipeline.
 *
 * <p>Originally replaced one {@code @Value} injection in
 * {@link com.incidentplatform.ingestion.normalizer.PrometheusNormalizer}:
 * {@code ingestion.prometheus.max-batch-size}. Now also holds
 * {@code ingestion.max-payload-bytes}, used by
 * {@link PayloadSizeLimitFilter}, and {@code ingestion.trusted-proxies},
 * used by {@link com.incidentplatform.ingestion.api.ClientIpResolver}.
 *
 * <h2>YAML configuration</h2>
 * <pre>{@code
 * ingestion:
 *   max-payload-bytes: ${INGESTION_MAX_PAYLOAD_BYTES:1048576}
 *   trusted-proxies: ${INGESTION_TRUSTED_PROXIES:}
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
        int maxPayloadBytes,

        /**
         * Exact IP addresses or CIDR ranges (e.g. {@code 192.168.1.0/24})
         * of proxies trusted to set {@code X-Forwarded-For}/
         * {@code X-Real-IP} accurately — used by
         * {@link com.incidentplatform.ingestion.api.ClientIpResolver}.
         * Parsed via Spring Security's
         * {@link org.springframework.security.web.util.matcher.IpAddressMatcher}
         * (already a transitive dependency via
         * {@code spring-boot-starter-security}) — supports both IPv4 and
         * IPv6.
         *
         * <h2>Fixed: X-Forwarded-For/X-Real-IP were previously trusted
         * unconditionally</h2>
         * {@code AlertIngestionController.resolveClientIp} used to read
         * these headers directly, with no check that the request actually
         * came through a proxy trusted to set them accurately. Both are
         * fully client-controlled — without this check, a caller reaching
         * the endpoint directly could set an arbitrary value per request,
         * trivially evading {@code RateLimitingService}'s IP-based rate
         * limiting.
         *
         * <p>Empty by default — when empty,
         * {@code ClientIpResolver} always uses
         * {@code request.getRemoteAddr()} directly and never reads either
         * header, regardless of what's sent. Deliberately fail-closed:
         * this module has run across different, evolving deployment
         * topologies over the course of this codebase's history (bare
         * Docker Compose with no explicit network subnet configured, and
         * potentially different ingress setups in Kubernetes), with no
         * single network range safe to hardcode as a default without
         * risking being either wrong (silently trusting nothing where a
         * real proxy is present) or actively unsafe (trusting a made-up
         * range that happens to overlap with a real deployment's
         * untrusted network). An operator running this behind a real
         * reverse proxy or ingress controller should explicitly set this
         * to that proxy's IP or CIDR range.
         */
        List<String> trustedProxies

) {
    public IngestionProperties {
        trustedProxies = trustedProxies != null ? List.copyOf(trustedProxies) : List.of();
    }

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