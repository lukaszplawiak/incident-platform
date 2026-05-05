package com.incidentplatform.postmortem.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class GeminiClientImpl implements GeminiClient {

    private static final Logger log =
            LoggerFactory.getLogger(GeminiClientImpl.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${gemini.api-key}")
    private String apiKey;

    @Value("${gemini.model:gemini-2.0-flash}")
    private String model;

    public GeminiClientImpl(@Qualifier("geminiRestClient") RestClient restClient,
                            ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Retry(name = "gemini", fallbackMethod = "generateFallback")
    @CircuitBreaker(name = "gemini", fallbackMethod = "generateFallback")
    @Override
    public String generate(String prompt) {
        log.debug("Sending request to Gemini API, model={}, " +
                "promptLength={}", model, prompt.length());

        final String requestBody = buildRequestBody(prompt);

        final String uri = "/v1beta/models/{model}:generateContent";

        try {
            final String responseBody = restClient.post()
                    .uri(uri, model)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            final String generated = extractTextFromResponse(responseBody);

            log.debug("Gemini API response received, " +
                    "responseLength={}", generated.length());

            return generated;

        } catch (RestClientException e) {
            throw new GeminiException(
                    "Gemini API request failed: " + e.getMessage(), e);
        }
    }

    public String generateFallback(String prompt, Exception ex) {
        log.error("Gemini API unavailable after retries or circuit breaker " +
                "is OPEN: {}", ex.getMessage());
        throw new GeminiException(
                "Gemini API unavailable: " + ex.getMessage(), ex);
    }

    private String buildRequestBody(String prompt) {
        try {
            final ObjectNode root = objectMapper.createObjectNode();
            final ArrayNode contents = root.putArray("contents");
            final ObjectNode content = contents.addObject();
            final ArrayNode parts = content.putArray("parts");
            parts.addObject().put("text", prompt);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new GeminiException(
                    "Failed to build Gemini request body", e);
        }
    }

    private String extractTextFromResponse(String responseBody) {
        try {
            final JsonNode root = objectMapper.readTree(responseBody);
            final JsonNode text = root
                    .path("candidates")
                    .path(0)
                    .path("content")
                    .path("parts")
                    .path(0)
                    .path("text");

            if (text.isMissingNode() || text.isNull()) {
                throw new GeminiException(
                        "Gemini response missing text field. " +
                                "Response: " + responseBody);
            }

            return text.asText();

        } catch (GeminiException e) {
            throw e;
        } catch (Exception e) {
            throw new GeminiException(
                    "Failed to parse Gemini response: " + e.getMessage(), e);
        }
    }
}