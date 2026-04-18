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
                log.debug("No oncall found for tenantId={}, role={}",
                        tenantId, role);
                return Optional.empty();
            }

            final JsonNode json = objectMapper.readTree(responseBody);
            final OncallInfo info = new OncallInfo(
                    json.path("userId").asText(null),
                    json.path("userName").asText(null),
                    json.path("email").asText(null),
                    json.path("phone").asText(null),
                    json.path("slackUserId").asText(null),
                    json.path("role").asText(null)
            );

            log.debug("Current oncall found: tenantId={}, role={}, userId={}",
                    tenantId, role, info.userId());

            return Optional.of(info);

        } catch (RestClientException e) {
            log.warn("Failed to fetch oncall from oncall-service: " +
                            "tenantId={}, role={}, error={}",
                    tenantId, role, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.error("Unexpected error fetching oncall: tenantId={}, " +
                    "role={}, error={}", tenantId, role, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<OncallInfo> getCurrentOncallFallback(
            String tenantId, String role, Exception ex) {
        log.warn("OncallClient circuit breaker OPEN: tenantId={}, role={}, " +
                "using fallback. Error: {}", tenantId, role, ex.getMessage());
        return Optional.empty();
    }
}