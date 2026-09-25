package com.incidentplatform.ingestion.apikey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.shared.observability.ClientFallbackMetrics;
import com.incidentplatform.shared.security.ServiceNames;
import com.incidentplatform.shared.security.ServiceTokenProvider;
import com.incidentplatform.shared.security.TokenPurposes;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * HTTP client for auth-service's {@code POST /api/v1/internal/api-keys/introspect}
 * (backlog #0-16). Same shape as notification-service's
 * {@code SlackWorkspaceClientImpl} (backlog #0-30): {@code @Retry} +
 * {@code @CircuitBreaker} on the interface method, and a fallback that records
 * {@link ClientFallbackMetrics} and throws instead of pretending to have an
 * answer.
 *
 * <h2>Authentication</h2>
 * ingestion-service cannot know the tenant before asking, so it does not use a
 * tenant-bound service token: it sends a purpose token
 * ({@link TokenPurposes#API_KEY_INTROSPECTION}, {@code aud=auth-service}) that
 * auth-service accepts on this one route only. A 401/403 from auth-service ends
 * in the fallback as {@code reason="auth"} in {@code service_client_fallback_total}
 * — a misconfiguration, not an outage (backlog #0-17 alerts on it).
 *
 * <h2>What is sent</h2>
 * Only the SHA-256 hash of the key, never the raw key, so the key the tenant
 * configured does not travel further than the service it was sent to.
 *
 * <p>Not injected directly by callers — {@link CachingApiKeyIntrospectionClient}
 * ({@code @Primary}) wraps this bean.
 */
@Component
public class ApiKeyIntrospectionClientImpl implements ApiKeyIntrospectionClient {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyIntrospectionClientImpl.class);

    static final String CLIENT_NAME = "api-key-introspection";
    private static final String PATH = "/api/v1/internal/api-keys/introspect";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ServiceTokenProvider serviceTokenProvider;
    private final ClientFallbackMetrics fallbackMetrics;
    private final String authServiceBaseUrl;

    public ApiKeyIntrospectionClientImpl(
            @Qualifier("ingestionServiceRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            ServiceTokenProvider serviceTokenProvider,
            ClientFallbackMetrics fallbackMetrics,
            @Value("${auth-service.base-url:http://localhost:8087}") String authServiceBaseUrl) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.serviceTokenProvider = serviceTokenProvider;
        this.fallbackMetrics = fallbackMetrics;
        this.authServiceBaseUrl = authServiceBaseUrl;
    }

    @Retry(name = CLIENT_NAME)
    @CircuitBreaker(name = CLIENT_NAME, fallbackMethod = "introspectFallback")
    @Override
    public Optional<IntrospectedApiKey> introspect(String keyHash) {
        final String body = restClient.post()
                .uri(authServiceBaseUrl + PATH)
                .header("Authorization", "Bearer " + serviceTokenProvider.getPurposeToken(
                        TokenPurposes.API_KEY_INTROSPECTION, ServiceNames.AUTH_SERVICE))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("keyHash", keyHash))
                .retrieve()
                .body(String.class);

        // {"active":false} is a successful answer — returned inside the proxied
        // method so the breaker counts it as a success: a burst of wrong keys
        // must not open the circuit and lock out every valid key.
        return parse(body);
    }

    /**
     * Resilience4j fallback — a real failure or an open circuit. Records the
     * fallback metric, then throws: the caller must answer 503, not 401.
     */
    @SuppressWarnings("unused")
    Optional<IntrospectedApiKey> introspectFallback(String keyHash, Exception e) {
        log.warn("auth-service could not introspect an API key: error={}", e.getMessage());
        fallbackMetrics.record(CLIENT_NAME, ServiceNames.AUTH_SERVICE, e);
        throw new ApiKeyIntrospectionUnavailableException(
                "auth-service could not answer the API key introspection", e);
    }

    private Optional<IntrospectedApiKey> parse(String body) {
        final JsonNode json;
        try {
            json = objectMapper.readTree(body == null ? "" : body);
        } catch (Exception e) {
            // Unchecked, so the proxy records it as a failure like a 5xx.
            throw new IllegalStateException("Unparseable introspection response", e);
        }
        if (json == null || !json.path("active").asBoolean(false)) {
            return Optional.empty();
        }
        final List<String> scopes = new ArrayList<>();
        json.path("scopes").forEach(scope -> scopes.add(scope.asText()));
        final String teamId = json.path("teamId").asText(null);
        final String expiresAt = json.path("expiresAt").asText(null);
        return Optional.of(new IntrospectedApiKey(
                UUID.fromString(json.path("keyId").asText()),
                json.path("tenantId").asText(null),
                teamId == null ? null : UUID.fromString(teamId),
                scopes,
                expiresAt == null ? null : Instant.parse(expiresAt)));
    }
}
