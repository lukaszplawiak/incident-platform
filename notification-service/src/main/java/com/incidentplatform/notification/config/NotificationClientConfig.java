package com.incidentplatform.notification.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * HTTP client configuration for inter-service calls in notification-service.
 *
 * <p>Previously received {@code notification.client.*} via {@code @Value}
 * constructor injection. Now receives a single {@link NotificationClientProperties}
 * record — all HTTP client configuration in one place with type safety.
 *
 * <p>See original Javadoc for rationale on JdkClientHttpRequestFactory
 * and timeout values.
 */
@Configuration
@EnableConfigurationProperties(NotificationClientProperties.class)
public class NotificationClientConfig {

    private final NotificationClientProperties properties;

    public NotificationClientConfig(NotificationClientProperties properties) {
        this.properties = properties;
    }

    @Bean("notificationServiceRestClient")
    public RestClient notificationServiceRestClient(RestClient.Builder builder) {
        final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()))
                .build();

        final JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(properties.readTimeoutSeconds()));

        return builder
                .requestFactory(factory)
                .build();
    }
}