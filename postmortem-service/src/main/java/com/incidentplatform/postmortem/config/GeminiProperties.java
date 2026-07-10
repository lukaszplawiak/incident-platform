package com.incidentplatform.postmortem.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly-typed, validated configuration for the Gemini AI client.
 *
 * <p>Replaces four scattered {@code @Value} injections across two classes:
 * <ul>
 *   <li>{@link GeminiConfig}: {@code gemini.base-url},
 *       {@code gemini.timeout-seconds}</li>
 *   <li>{@link com.incidentplatform.postmortem.client.GeminiClientImpl}:
 *       {@code gemini.api-key}, {@code gemini.model}</li>
 * </ul>
 *
 * <h2>Security note — api-key</h2>
 * The API key is a secret and must never be committed to source control.
 * Always inject via environment variable: {@code GEMINI_API_KEY}.
 * The default value {@code "your-api-key-here"} in {@code application.yml}
 * is a placeholder — the application will start but Gemini calls will fail
 * with 401 until a real key is provided.
 *
 * <h2>YAML configuration</h2>
 * <pre>{@code
 * gemini:
 *   api-key: ${GEMINI_API_KEY}
 *   base-url: ${GEMINI_BASE_URL:https://generativelanguage.googleapis.com}
 *   model: ${GEMINI_MODEL:gemini-2.0-flash}
 *   timeout-seconds: ${GEMINI_TIMEOUT_SECONDS:30}
 * }</pre>
 */
@ConfigurationProperties(prefix = "gemini")
@Validated
public record GeminiProperties(

        /**
         * Gemini AI API key. Must be provided via {@code GEMINI_API_KEY}
         * environment variable — never hardcoded.
         */
        @NotBlank(message = "gemini.api-key must not be blank")
        String apiKey,

        /**
         * Base URL of the Gemini API endpoint.
         * Default: https://generativelanguage.googleapis.com
         */
        @NotBlank(message = "gemini.base-url must not be blank")
        String baseUrl,

        /**
         * Gemini model identifier to use for postmortem generation.
         * Default: gemini-2.0-flash
         */
        @NotBlank(message = "gemini.model must not be blank")
        String model,

        /**
         * HTTP connection and read timeout for Gemini API calls (seconds).
         * Gemini generation can take 3-15 seconds — default of 30s provides
         * headroom without blocking indefinitely.
         * Default: 30
         */
        @Positive(message = "gemini.timeout-seconds must be positive")
        int timeoutSeconds

) {}