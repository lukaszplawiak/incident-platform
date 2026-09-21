package com.incidentplatform.shared.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClientFallbackMetrics")
class ClientFallbackMetricsTest {

    private SimpleMeterRegistry registry;
    private ClientFallbackMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new ClientFallbackMetrics(registry);
    }

    private double count(String reason) {
        return registry.counter(ClientFallbackMetrics.METRIC_NAME,
                "client", "oncall", "target", "oncall-service", "reason", reason).count();
    }

    @Test
    @DisplayName("401 and 403 are counted as auth — a misconfiguration, not an outage")
    void authFailuresAreAuth() {
        metrics.record("oncall", "oncall-service",
                HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "u",
                        HttpHeaders.EMPTY, new byte[0], null));
        metrics.record("oncall", "oncall-service",
                HttpClientErrorException.create(HttpStatus.FORBIDDEN, "f",
                        HttpHeaders.EMPTY, new byte[0], null));

        assertThat(count("auth")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("other 4xx, 5xx and connection failures get their own reasons")
    void otherReasons() {
        metrics.record("oncall", "oncall-service",
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "b",
                        HttpHeaders.EMPTY, new byte[0], null));
        metrics.record("oncall", "oncall-service",
                HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "g",
                        HttpHeaders.EMPTY, new byte[0], null));
        metrics.record("oncall", "oncall-service",
                new ResourceAccessException("connection refused"));
        metrics.record("oncall", "oncall-service", new IllegalStateException("boom"));
        metrics.record("oncall", "oncall-service", new IllegalArgumentException("bad tenant"));

        assertThat(count("client_error")).isEqualTo(1.0);
        assertThat(count("server_error")).isEqualTo(1.0);
        assertThat(count("unreachable")).isEqualTo(1.0);
        assertThat(count("other")).isEqualTo(1.0);
        assertThat(count("invalid_argument")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an open circuit breaker is recognised without depending on Resilience4j")
    void circuitOpen() {
        class CallNotPermittedException extends RuntimeException { }

        metrics.record("oncall", "oncall-service", new CallNotPermittedException());

        assertThat(count("circuit_open")).isEqualTo(1.0);
    }
}
