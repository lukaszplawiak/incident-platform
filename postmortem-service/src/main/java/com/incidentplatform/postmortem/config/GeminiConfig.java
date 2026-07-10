package com.incidentplatform.postmortem.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Configures the {@link RestClient} used for Gemini AI API calls.
 *
 * <p>Previously this class received {@code gemini.base-url} and
 * {@code gemini.timeout-seconds} via {@code @Value} constructor injection.
 * It now receives a single {@link GeminiProperties} record — all Gemini
 * configuration in one place, with type safety and Bean Validation.
 */
@Configuration
@EnableConfigurationProperties(GeminiProperties.class)
public class GeminiConfig {

    private final GeminiProperties properties;

    public GeminiConfig(GeminiProperties properties) {
        this.properties = properties;
    }

    @Bean("geminiRestClient")
    public RestClient geminiRestClient(RestClient.Builder builder) {
        final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .build();

        final JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(properties.timeoutSeconds()));

        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }
}