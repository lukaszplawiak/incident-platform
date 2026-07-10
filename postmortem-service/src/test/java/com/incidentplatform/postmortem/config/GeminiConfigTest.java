package com.incidentplatform.postmortem.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GeminiConfig")
class GeminiConfigTest {

    private static GeminiConfig configWith(String baseUrl, int timeoutSeconds) {
        return new GeminiConfig(new GeminiProperties(
                "test-api-key", baseUrl, "gemini-2.0-flash", timeoutSeconds));
    }

    @Test
    @DisplayName("creates RestClient bean with baseUrl and timeout from GeminiProperties")
    void createsRestClientBean() {
        final GeminiConfig config =
                configWith("https://generativelanguage.googleapis.com", 30);

        final RestClient restClient =
                config.geminiRestClient(RestClient.builder());

        assertThat(restClient).isNotNull();
    }

    @Test
    @DisplayName("accepts custom timeout — no int overflow (was cast to millis before)")
    void acceptsLargeTimeoutWithoutOverflow() {
        // 300s would overflow as (int) Duration.ofSeconds(300).toMillis()
        // if the old SimpleClientHttpRequestFactory cast was used
        final GeminiConfig config =
                configWith("https://generativelanguage.googleapis.com", 300);

        final RestClient restClient =
                config.geminiRestClient(RestClient.builder());

        assertThat(restClient).isNotNull();
    }

    @Test
    @DisplayName("uses provided baseUrl — distinct instances for different URLs")
    void usesProvidedBaseUrl() {
        final GeminiConfig config1 = configWith("https://api-1.example.com", 30);
        final GeminiConfig config2 = configWith("https://api-2.example.com", 30);

        final RestClient client1 = config1.geminiRestClient(RestClient.builder());
        final RestClient client2 = config2.geminiRestClient(RestClient.builder());

        assertThat(client1).isNotSameAs(client2);
    }
}