package com.incidentplatform.notification.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.shared.security.ServiceTokenProvider;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

@Component
public class OncallClientImpl implements OncallClient {

    private static final Logger log =
            LoggerFactory.getLogger(OncallClientImpl.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ServiceTokenProvider serviceTokenProvider;

    @Value("${oncall-service.base-url:http://localhost:8086}")
    private String oncallServiceBaseUrl;

    public OncallClientImpl(RestClient.Builder restClientBuilder,
                            ObjectMapper objectMapper,
                            ServiceTokenProvider serviceTokenProvider) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.serviceTokenProvider = serviceTokenProvider;
    }

    @CircuitBreaker(name = "oncall", fallbackMethod = "getCurrentOncallFallback")
    @Override
    public Optional<OncallInfo> getCurrentOncall(String tenantId, String role) {
        log.debug("Fetching current oncall: tenantId={}, role={}",
                tenantId, role);

        try {
            final String responseBody = restClient.get()
                    .uri(oncallServiceBaseUrl +
                            "/api/v1/oncall/current?role=" + role)
                    .header("Authorization",
                            "Bearer " + serviceTokenProvider.getToken())
                    .header("X-Tenant-Id", tenantId)
                    .retrieve()
                    .body(String.class);

            if (responseBody == null || responseBody.isBlank()) {
                return Optional.empty();
            }

            return Optional.of(parseOncallInfo(responseBody));

        } catch (RestClientException e) {
            log.warn("Failed to fetch oncall: tenantId={}, role={}, error={}",
                    tenantId, role, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.error("Unexpected error fetching oncall: tenantId={}, " +
                    "role={}, error={}", tenantId, role, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<OncallInfo> findBySlackUserId(String slackUserId) {
        log.debug("Looking up user by slackUserId: {}", slackUserId);

        try {
            final String responseBody = restClient.get()
                    .uri(oncallServiceBaseUrl +
                            "/api/v1/oncall/by-slack/" + slackUserId)
                    .header("Authorization",
                            "Bearer " + serviceTokenProvider.getToken())
                    .retrieve()
                    .body(String.class);

            if (responseBody == null || responseBody.isBlank()) {
                log.warn("No user found for slackUserId: {}", slackUserId);
                return Optional.empty();
            }

            final JsonNode json = objectMapper.readTree(responseBody);
            final OncallInfo info = new OncallInfo(
                    json.path("userId").asText(null),
                    json.path("userName").asText(null),
                    null,
                    null,
                    json.path("slackUserId").asText(null),
                    null
            );

            log.debug("User found for slackUserId={}: userId={}",
                    slackUserId, info.userId());
            return Optional.of(info);

        } catch (RestClientException e) {
            log.warn("Failed to lookup user by slackUserId={}: {}",
                    slackUserId, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.error("Unexpected error looking up slackUserId={}: {}",
                    slackUserId, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<OncallInfo> getCurrentOncallFallback(
            String tenantId, String role, Exception ex) {
        log.warn("OncallClient circuit breaker OPEN: tenantId={}, role={}, " +
                "using fallback. Error: {}", tenantId, role, ex.getMessage());
        return Optional.empty();
    }

    private OncallInfo parseOncallInfo(String responseBody) throws Exception {
        final JsonNode json = objectMapper.readTree(responseBody);
        return new OncallInfo(
                json.path("userId").asText(null),
                json.path("userName").asText(null),
                json.path("email").asText(null),
                json.path("phone").asText(null),
                json.path("slackUserId").asText(null),
                json.path("role").asText(null)
        );
    }
}