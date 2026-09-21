package com.incidentplatform.shared.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Counts every time a service-to-service HTTP client falls back to its
 * fail-open default.
 *
 * <h2>Added (backlog #0-11): fail-open must not mean invisible</h2>
 * The clients that call oncall-service and incident-service deliberately
 * fail open — a broken dependency must not stop an incident from being
 * handled. But every failure, including a permanent 401, was only a
 * {@code WARN} log line, so service tokens being rejected on every call
 * went unnoticed until the effect (notifications reaching the wrong
 * person) was traced backwards. A counter turns "the fallback is firing"
 * into something an alert can watch:
 * {@code service_client_fallback_total{client, target, reason}}.
 *
 * <p>Tags are deliberately low-cardinality: no tenant, no URL, no message.
 * {@code reason} is {@code auth} (401/403 — a misconfiguration, not an
 * outage), {@code invalid_argument} (the client was called with a bad
 * argument, e.g. a malformed tenant id — a defect in the caller or the
 * payload, not an outage), {@code client_error}, {@code server_error},
 * {@code unreachable}, {@code circuit_open} or {@code other}.
 *
 * <p>An {@code auth} fallback is also logged at {@code ERROR} here, once for
 * every client, so that a rejected service token cannot be a mere
 * {@code WARN} line in one client and an {@code ERROR} in another.
 */
@Component
public class ClientFallbackMetrics {

    public static final String METRIC_NAME = "service.client.fallback";

    private static final Logger log =
            LoggerFactory.getLogger(ClientFallbackMetrics.class);

    private final MeterRegistry meterRegistry;

    public ClientFallbackMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * @param client short name of the calling client (e.g. {@code oncall})
     * @param target the service being called (e.g. {@code oncall-service})
     * @param cause  what made the client fall back
     */
    public void record(String client, String target, Throwable cause) {
        final String reason = reasonOf(cause);
        meterRegistry.counter(METRIC_NAME,
                        "client", client,
                        "target", target,
                        "reason", reason)
                .increment();

        if ("auth".equals(reason)) {
            log.error("{} rejected the service token from client {} — this is " +
                            "an authentication/authorization misconfiguration, " +
                            "not an outage: {}",
                    target, client, cause.getMessage());
        }
    }

    static String reasonOf(Throwable cause) {
        if (cause instanceof HttpClientErrorException.Unauthorized
                || cause instanceof HttpClientErrorException.Forbidden) {
            return "auth";
        }
        if (cause instanceof IllegalArgumentException) {
            return "invalid_argument";
        }
        if (cause instanceof HttpStatusCodeException statusException) {
            return statusException.getStatusCode().is5xxServerError()
                    ? "server_error" : "client_error";
        }
        if (cause instanceof ResourceAccessException) {
            return "unreachable";
        }
        // Resilience4j is not a dependency of shared; match by name rather
        // than pulling it in for one exception type.
        if (cause != null
                && "CallNotPermittedException".equals(cause.getClass().getSimpleName())) {
            return "circuit_open";
        }
        return "other";
    }
}
