package com.incidentplatform.notification.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * HTTP client timeout configuration for inter-service calls in notification-service.
 *
 * <p>Replaces two {@code @Value} injections in {@link NotificationClientConfig}:
 * {@code notification.client.connect-timeout-seconds} and
 * {@code notification.client.read-timeout-seconds}.
 *
 * <h2>YAML configuration</h2>
 * <pre>{@code
 * notification:
 *   client:
 *     connect-timeout-seconds: ${NOTIFICATION_CLIENT_CONNECT_TIMEOUT:3}
 *     read-timeout-seconds: ${NOTIFICATION_CLIENT_READ_TIMEOUT:5}
 * }</pre>
 */
@ConfigurationProperties(prefix = "notification.client")
@Validated
public record NotificationClientProperties(

        /**
         * TCP connection timeout for inter-service calls (seconds).
         * 3s is appropriate for within-cluster calls — longer means service is down.
         */
        @Positive(message = "notification.client.connect-timeout-seconds must be positive")
        int connectTimeoutSeconds,

        /**
         * Read timeout for inter-service calls (seconds).
         * 5s allows for normal processing while preventing indefinite thread blocking.
         */
        @Positive(message = "notification.client.read-timeout-seconds must be positive")
        int readTimeoutSeconds

) {}