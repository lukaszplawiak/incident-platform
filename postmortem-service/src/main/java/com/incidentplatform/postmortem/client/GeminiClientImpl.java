package com.incidentplatform.postmortem.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.incidentplatform.postmortem.config.GeminiProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class GeminiClientImpl implements GeminiClient {

    private static final Logger log =
            LoggerFactory.getLogger(GeminiClientImpl.class);

    // Cap on how much of a Gemini response body gets embedded in an
    // exception message (and therefore logged). Previously the entire raw
    // response was included unconditionally — harmless in practice for
    // Gemini's own error/response format, but more than needed for
    // debugging and worth bounding on principle.
    private static final int MAX_LOGGED_RESPONSE_CHARS = 500;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    public GeminiClientImpl(
            @Qualifier("geminiRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            GeminiProperties properties) {
        this.restClient   = restClient;
        this.objectMapper = objectMapper;
        this.apiKey       = properties.apiKey();
        this.model        = properties.model();
    }

    /**
     * Fixed: previously took one flat {@code prompt} string containing
     * both the fixed instructions and the untrusted incident title —
     * see {@link GeminiClient}'s Javadoc for the full prompt-injection
     * rationale. {@code systemInstruction} and {@code userContent} are
     * now sent to Gemini's own {@code system_instruction} and
     * {@code contents} fields respectively — structurally separate,
     * with {@code system_instruction} given higher priority by the model.
     */
    @Retry(name = "gemini", fallbackMethod = "generateFallback")
    @CircuitBreaker(name = "gemini", fallbackMethod = "generateFallback")
    @Override
    public String generate(String systemInstruction, String userContent) {
        log.debug("Sending request to Gemini API, model={}, " +
                        "systemInstructionLength={}, userContentLength={}",
                model, systemInstruction.length(), userContent.length());

        final String requestBody = buildRequestBody(systemInstruction, userContent);

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

    public String generateFallback(String systemInstruction, String userContent,
                                   Exception ex) {
        log.error("Gemini API unavailable after retries or circuit breaker " +
                "is OPEN: {}", ex.getMessage());
        throw new GeminiException(
                "Gemini API unavailable: " + ex.getMessage(), ex);
    }

    private String buildRequestBody(String systemInstruction, String userContent) {
        try {
            final ObjectNode root = objectMapper.createObjectNode();

            final ObjectNode systemInstructionNode = root.putObject("system_instruction");
            systemInstructionNode.putArray("parts")
                    .addObject().put("text", systemInstruction);

            final ArrayNode contents = root.putArray("contents");
            final ObjectNode content = contents.addObject();
            final ArrayNode parts = content.putArray("parts");
            parts.addObject().put("text", userContent);

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
                        "Gemini response missing text field. Response: " +
                                truncateForLogging(responseBody));
            }

            return text.asText();

        } catch (GeminiException e) {
            throw e;
        } catch (Exception e) {
            throw new GeminiException(
                    "Failed to parse Gemini response: " + e.getMessage(), e);
        }
    }

    private static String truncateForLogging(String value) {
        if (value == null || value.length() <= MAX_LOGGED_RESPONSE_CHARS) {
            return value;
        }
        return value.substring(0, MAX_LOGGED_RESPONSE_CHARS) + "... [truncated]";
    }
}