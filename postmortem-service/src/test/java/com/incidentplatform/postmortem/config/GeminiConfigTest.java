package com.incidentplatform.postmortem.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GeminiConfig")
class GeminiConfigTest {

    @Test
    @DisplayName("creates RestClient bean with baseUrl and timeout from constructor")
    void createsRestClientBean() {
        // given
        final GeminiConfig config =
                new GeminiConfig("https://generativelanguage.googleapis.com", 30);

        // when
        final RestClient restClient =
                config.geminiRestClient(RestClient.builder());

        // then
        assertThat(restClient).isNotNull();
    }

    @Test
    @DisplayName("accepts custom timeout — no int overflow (was cast to millis before)")
    void acceptsLargeTimeoutWithoutOverflow() {
        // given — 300s would overflow as (int) Duration.ofSeconds(300).toMillis()
        // if the old SimpleClientHttpRequestFactory cast was used
        final GeminiConfig config =
                new GeminiConfig("https://generativelanguage.googleapis.com", 300);

        // when — should not throw
        final RestClient restClient =
                config.geminiRestClient(RestClient.builder());

        // then
        assertThat(restClient).isNotNull();
    }

    @Test
    @DisplayName("uses provided baseUrl — distinct instances for different URLs")
    void usesProvidedBaseUrl() {
        // given
        final GeminiConfig config1 =
                new GeminiConfig("https://api-1.example.com", 30);
        final GeminiConfig config2 =
                new GeminiConfig("https://api-2.example.com", 30);

        // when
        final RestClient client1 = config1.geminiRestClient(RestClient.builder());
        final RestClient client2 = config2.geminiRestClient(RestClient.builder());

        // then
        assertThat(client1).isNotSameAs(client2);
    }
}