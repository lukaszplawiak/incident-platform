package com.incidentplatform.notification.client;

import com.incidentplatform.shared.security.ServiceTokenProvider;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
 * <h2>Fixed: two separate bugs, both defeating the circuit breaker</h2>
 * <ol>
 *   <li>{@code fallbackMethod = "acknowledgeIncidentFallback"} referenced
 *       a method that did not exist anywhere in this class. Resilience4j
 *       resolves the fallback method by reflection the first time it's
 *       actually needed (circuit OPEN, or a matching exception thrown) —
 *       with no such method present, that lookup itself would fail
 *       instead of gracefully returning {@code false} as intended. Added
 *       below.</li>
 *   <li>{@code acknowledgeIncident} wrapped its body in
 *       {@code catch (RestClientException e) { ...; return false; }} —
 *       swallowing the exception before the {@code @CircuitBreaker}
 *       proxy around the method call could ever see it, so
 *       {@code circuitBreaker.recordFailure(...)} was never invoked and
 *       the breaker could never open. The previous class Javadoc here
 *       described this as intentional ("caught by the broad catch
 *       (Exception e) block ... same as a network failure") — that
 *       description conflated "the caller gets a safe false either way"
 *       (true, and still true after this fix) with "the circuit breaker
 *       is doing its job" (false — it was never given the chance to).
 *       The catch is removed; real failures now propagate to the proxy,
 *       and the (now-existing) fallback method is what returns
 *       {@code false} to the caller.</li>
 * </ol>
 * Same root cause independently found and fixed in
 * {@code OncallClientImpl} (this package) and {@code DeduplicationService}
 * (ingestion-service), using {@code escalation-service}'s
 * {@code OncallServiceClient} as the reference pattern for what a
 * correctly-wired circuit breaker looks like in this codebase.
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
    }

    /**
     * Resilience4j fallback — called both on a real failure (HTTP error,
     * connection issue) and when the circuit is already OPEN (in which
     * case {@code e} is a
     * {@link io.github.resilience4j.circuitbreaker.CallNotPermittedException}).
     * This method did not exist before this fix — see this class's
     * Javadoc.
     */
    @SuppressWarnings("unused")
    boolean acknowledgeIncidentFallback(UUID incidentId, String tenantId,
                                        UUID acknowledgedByUserId, Exception e) {
        log.error("Failed to acknowledge incident (circuit breaker fallback): " +
                        "incidentId={}, tenant={}, error={}",
                incidentId, tenantId, e.getMessage());
        return false;
    }
}