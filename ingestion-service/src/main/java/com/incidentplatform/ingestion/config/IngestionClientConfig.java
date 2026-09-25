package com.incidentplatform.ingestion.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * {@link RestClient} for ingestion-service's calls to other services, with
 * connect and read timeouts (CLAUDE.md: a new HTTP client needs both). Same
 * construction as notification-service's {@code NotificationClientConfig}.
 */
@Configuration
@EnableConfigurationProperties(IngestionClientProperties.class)
public class IngestionClientConfig {

    @Bean("ingestionServiceRestClient")
    public RestClient ingestionServiceRestClient(RestClient.Builder builder,
                                                 IngestionClientProperties properties) {
        final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()))
                .build();
        final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(properties.readTimeoutSeconds()));
        return builder.requestFactory(factory).build();
    }
}
