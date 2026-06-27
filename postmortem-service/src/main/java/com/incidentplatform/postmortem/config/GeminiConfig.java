package com.incidentplatform.postmortem.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class GeminiConfig {

    private final String baseUrl;
    private final int timeoutSeconds;

    public GeminiConfig(
            @Value("${gemini.base-url}") String baseUrl,
            @Value("${gemini.timeout-seconds:30}") int timeoutSeconds) {
        this.baseUrl = baseUrl;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Bean("geminiRestClient")
    public RestClient geminiRestClient(RestClient.Builder builder) {
        final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        final JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

        return builder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}