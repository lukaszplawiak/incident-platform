package com.incidentplatform.notification.client;

import com.incidentplatform.shared.security.ServiceTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.UUID;

@Component
public class IncidentAckClient {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentAckClient.class);

    private final RestClient restClient;
    private final ServiceTokenProvider serviceTokenProvider;

    @Value("${incident-service.base-url:http://localhost:8082}")
    private String incidentServiceBaseUrl;

    public IncidentAckClient(RestClient.Builder restClientBuilder,
                             ServiceTokenProvider serviceTokenProvider) {
        this.restClient = restClientBuilder.build();
        this.serviceTokenProvider = serviceTokenProvider;
    }

    public boolean acknowledgeIncident(UUID incidentId,
                                       String tenantId,
                                       UUID acknowledgedByUserId) {
        log.info("Acknowledging incident via REST: incidentId={}, tenant={}, " +
                "userId={}", incidentId, tenantId, acknowledgedByUserId);

        try {
            final Map<String, Object> body = Map.of(
                    "status", "ACKNOWLEDGED",
                    "acknowledgedBy", acknowledgedByUserId.toString()
            );

            restClient.patch()
                    .uri(incidentServiceBaseUrl +
                            "/api/v1/incidents/" + incidentId + "/status")
                    .header("Authorization",
                            "Bearer " + serviceTokenProvider.getToken())
                    .header("X-Tenant-Id", tenantId)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Incident acknowledged successfully: incidentId={}, " +
                    "tenant={}", incidentId, tenantId);
            return true;

        } catch (RestClientException e) {
            log.error("Failed to acknowledge incident: incidentId={}, " +
                    "tenant={}, error={}", incidentId, tenantId, e.getMessage());
            return false;
        }
    }
}