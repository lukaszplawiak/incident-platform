package com.incidentplatform.notification.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * HTTP client configuration for inter-service calls in notification-service.
 *
 * <p>Provides a shared {@link RestClient} with explicit connection and read
 * timeouts for calls to {@code incident-service} and {@code oncall-service}.
 * Without explicit timeouts, the default {@code RestClient} uses
 * {@code SimpleClientHttpRequestFactory} with no timeout — a single
 * unresponsive downstream service exhausts the {@code slackTaskExecutor}
 * thread pool (corePoolSize=2, maxPoolSize=5), causing all Slack ACK button
 * clicks to queue up indefinitely.
 *
 * <h2>Why JdkClientHttpRequestFactory</h2>
 * {@code SimpleClientHttpRequestFactory} (the default) wraps
 * {@code HttpURLConnection}: blocking I/O, no connection pooling, no HTTP/2,
 * deprecated in Spring 6.1. {@link JdkClientHttpRequestFactory} wraps
 * {@code java.net.http.HttpClient} (Java 11+): non-blocking under the hood,
 * connection pooling, HTTP/2 support, proper {@code Duration}-based timeouts.
 *
 * <h2>Timeout values</h2>
 * Connect timeout 3s: inter-service calls are within the same cluster — a
 * 3-second connect timeout means the service is genuinely down, not just slow.
 * Read timeout 5s: enough for normal processing; combined with the circuit
 * breaker this prevents prolonged thread blocking.
 */
@Configuration
public class NotificationClientConfig {

    private final int connectTimeoutSeconds;
    private final int readTimeoutSeconds;

    public NotificationClientConfig(@Value("${notification.client.connect-timeout-seconds:3}")
                                    int connectTimeoutSeconds,
                                    @Value("${notification.client.read-timeout-seconds:5}")
                                    int readTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    @Bean("notificationServiceRestClient")
    public RestClient notificationServiceRestClient(RestClient.Builder builder) {
        final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();

        final JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));

        return builder
                .requestFactory(factory)
                .build();
    }
}