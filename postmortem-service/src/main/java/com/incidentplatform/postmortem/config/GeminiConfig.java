package com.incidentplatform.postmortem.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class GeminiConfig {

    @Value("${gemini.base-url}")
    private String baseUrl;

    @Value("${gemini.timeout-seconds:30}")
    private int timeoutSeconds;

    @Bean("geminiRestClient")
    public RestClient geminiRestClient(RestClient.Builder builder) {
        final SimpleClientHttpRequestFactory factory =
                new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(
                (int) Duration.ofSeconds(timeoutSeconds).toMillis());
        factory.setReadTimeout(
                (int) Duration.ofSeconds(timeoutSeconds).toMillis());

        return builder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}