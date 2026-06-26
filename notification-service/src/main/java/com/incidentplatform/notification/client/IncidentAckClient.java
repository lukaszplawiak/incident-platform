package com.incidentplatform.notification.client;

import com.incidentplatform.shared.security.ServiceTokenProvider;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.UUID;

/**
 * HTTP client for acknowledging incidents in {@code incident-service}.
 *
 * <p>Called from {@link com.incidentplatform.notification.slack.SlackActionService}
 * in an {@code @Async} context ({@code slackTaskExecutor}, maxPoolSize=5).
 * Without a circuit breaker and connection timeout, a single unresponsive
 * {@code incident-service} blocks all 5 async threads indefinitely — causing
 * every subsequent Slack ACK button click to queue up until the pool is
 * exhausted.
 *
 * <h2>Circuit breaker behaviour</h2>
 * Uses the {@code incident-ack} circuit breaker instance configured in
 * {@code application.yml}. When the breaker is OPEN, {@link #acknowledgeIncidentFallback}
 * is called immediately without making an HTTP call — freeing the thread for
 * other work and giving {@code incident-service} time to recover.
 *
 * <p>Fallback returns {@code false} (acknowledgement failed) and logs a warning.
 * The caller ({@link com.incidentplatform.notification.slack.SlackActionService})
 * already handles {@code false} gracefully — it logs the failure and skips the
 * Slack message update. The Kafka message is not acknowledged, so the event will
 * be redelivered and the user can click the button again after recovery.
 */
@Component
public class IncidentAckClient {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentAckClient.class);

    private static final String STATUS_ACKNOWLEDGED = "ACKNOWLEDGED";
    private static final String CIRCUIT_BREAKER_NAME = "incident-ack";

    private final RestClient restClient;
    private final ServiceTokenProvider serviceTokenProvider;
    private final String incidentServiceBaseUrl;

    public IncidentAckClient(
            @Qualifier("notificationServiceRestClient") RestClient restClient,
            ServiceTokenProvider serviceTokenProvider,
            @Value("${incident-service.base-url:http://localhost:8082}")
            String incidentServiceBaseUrl) {
        this.restClient = restClient;
        this.serviceTokenProvider = serviceTokenProvider;
        this.incidentServiceBaseUrl = incidentServiceBaseUrl;
    }

    @CircuitBreaker(name = CIRCUIT_BREAKER_NAME,
            fallbackMethod = "acknowledgeIncidentFallback")
    public boolean acknowledgeIncident(UUID incidentId,
                                       String tenantId,
                                       UUID acknowledgedByUserId) {
        log.info("Acknowledging incident via REST: incidentId={}, tenant={}, " +
                "userId={}", incidentId, tenantId, acknowledgedByUserId);

        try {
            final Map<String, Object> body = Map.of(
                    "status", STATUS_ACKNOWLEDGED,
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

    /**
     * Called when the {@code incident-ack} circuit breaker is OPEN or when
     * all retries are exhausted. Returns {@code false} so the caller can log
     * the failure without crashing the async processing pipeline.
     */
    public boolean acknowledgeIncidentFallback(UUID incidentId,
                                               String tenantId,
                                               UUID acknowledgedByUserId,
                                               Exception ex) {
        log.warn("incident-ack circuit breaker OPEN or call failed: " +
                        "incidentId={}, tenant={}, error={}",
                incidentId, tenantId, ex.getMessage());
        return false;
    }
}