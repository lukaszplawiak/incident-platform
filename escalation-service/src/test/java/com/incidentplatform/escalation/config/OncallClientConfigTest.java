package com.incidentplatform.escalation.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Backlog #0-11: proves the timeout is real, against a server that accepts the
 * request and never answers — the "hung oncall-service" that would otherwise
 * block {@code EscalationScheduler} indefinitely.
 */
@DisplayName("OncallClientConfig — timeouts")
class OncallClientConfigTest {

    private HttpServer server;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void startHangingServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            try {
                // never answers within the test's read timeout
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
    }

    @Test
    @DisplayName("a call to a server that never answers fails with a read timeout instead of hanging")
    void readTimeoutFires() {
        final RestClient.Builder builder = RestClient.builder();
        new OncallClientConfig()
                .oncallClientTimeouts(Duration.ofSeconds(2), Duration.ofMillis(300))
                .customize(builder);
        final RestClient client = builder
                .baseUrl("http://localhost:" + server.getAddress().getPort())
                .build();

        final long start = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri("/api/v1/oncall/current")
                .retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class);
        final Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
    }
}
