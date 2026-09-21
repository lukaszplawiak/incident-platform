package com.incidentplatform.escalation.client;

import com.incidentplatform.escalation.dto.OncallUserDto;
import com.incidentplatform.shared.observability.ClientFallbackMetrics;
import com.incidentplatform.shared.security.ServiceNames;
import com.incidentplatform.shared.security.ServiceTokenProvider;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

/**
 * HTTP client for oncall-service — resolves the current on-call engineer
 * for a given team and role.
 *
 * <h2>Why synchronous HTTP, not Kafka</h2>
 * "Who is on-call for team X right now?" is a read query that requires
 * a current answer. Kafka request-reply would add latency, correlation ID
 * complexity, and reply-topic management — all overhead for a simple read.
 * RestClient with circuit breaker is the correct tool for synchronous
 * service-to-service reads.
 *
 * <h2>Circuit breaker</h2>
 * If oncall-service is unavailable, the circuit breaker opens after
 * {@code resilience4j.circuitbreaker.instances.oncall-service.failureRateThreshold}
 * consecutive failures. Fallback returns {@link Optional#empty()} — escalation
 * proceeds without on-call routing and logs a warning.
 *
 * <p>Fail-loudly: no silent fallback to a default user. A missing on-call
 * assignment should be visible in logs/alerts, not hidden.
 *
 * <h2>Fixed (backlog #0-11): the service JWT was never sent</h2>
 * This Javadoc used to say the request carries a service JWT, but the code
 * sent only {@code X-Tenant-Id}, which no HTTP filter reads. oncall-service
 * requires {@code ROLE_SERVICE} on {@code /api/v1/oncall/current}, so every
 * lookup was a 401 that surfaced only as the fail-open fallback below — and
 * {@code escalateTo} was therefore always null. The request now carries
 * {@code Authorization: Bearer <token>} from {@link ServiceTokenProvider},
 * a token minted for exactly this call's tenant. {@code X-Tenant-Id} stays
 * as an informational header; the tenant that counts is the signed claim.
 */
@Component
public class OncallServiceClient {

    private static final Logger log =
            LoggerFactory.getLogger(OncallServiceClient.class);

    private final RestClient restClient;
    private final ServiceTokenProvider serviceTokenProvider;
    private final ClientFallbackMetrics fallbackMetrics;

    public OncallServiceClient(
            RestClient.Builder restClientBuilder,
            ServiceTokenProvider serviceTokenProvider,
            ClientFallbackMetrics fallbackMetrics,
            @Value("${oncall.service.url}") String oncallServiceUrl) {
        this.restClient = restClientBuilder
                .baseUrl(oncallServiceUrl)
                .build();
        this.serviceTokenProvider = serviceTokenProvider;
        this.fallbackMetrics = fallbackMetrics;
    }

    /**
     * Returns the current on-call person for a team and role.
     *
     * @param tenantId  tenant identifier (forwarded as X-Tenant-Id header)
     * @param teamId    team UUID
     * @param role      "PRIMARY", "SECONDARY", or "MANAGER"
     * @return on-call user or empty if no active schedule / service unavailable
     */
    @CircuitBreaker(name = "oncall-service", fallbackMethod = "fallback")
    public Optional<OncallUserDto> getCurrentOncall(String tenantId,
                                                    UUID teamId,
                                                    String role) {
        try {
            final OncallUserDto response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/oncall/current")
                            .queryParam("teamId", teamId)
                            .queryParam("role", role)
                            .build())
                    .header("Authorization",
                            "Bearer " + serviceTokenProvider.getToken(
                                    tenantId, ServiceNames.ONCALL_SERVICE))
                    .header("X-Tenant-Id", tenantId)
                    .retrieve()
                    .onStatus(status -> status == HttpStatus.NO_CONTENT,
                            (req, res) -> {
                                // 204 = no active schedule — not an error
                            })
                    .body(OncallUserDto.class);

            if (response == null) {
                log.debug("No active on-call for teamId={}, role={}, tenant={}",
                        teamId, role, tenantId);
                return Optional.empty();
            }

            log.debug("On-call resolved: userId={}, email={}, teamId={}, role={}",
                    response.userId(), response.email(), teamId, role);

            return Optional.of(response);

        } catch (HttpClientErrorException.NotFound e) {
            log.debug("oncall-service returned 404 for teamId={}, role={}",
                    teamId, role);
            return Optional.empty();
        }
    }

    /**
     * Circuit breaker fallback — called when the call to oncall-service
     * fails or the circuit is open. Returns empty so escalation proceeds
     * without on-call routing. Logs a warning so the gap is visible in
     * monitoring, and counts it in {@link ClientFallbackMetrics}, which also
     * logs a rejected service token (401/403) at ERROR so it cannot be
     * mistaken for "no on-call" — the state that hid backlog #0-11.
     */
    @SuppressWarnings("unused")
    private Optional<OncallUserDto> fallback(String tenantId, UUID teamId,
                                             String role, Exception e) {
        fallbackMetrics.record("oncall", "oncall-service", e);
        log.warn("oncall-service call failed or circuit open — skipping " +
                        "on-call routing for teamId={}, role={}, tenant={}: {}",
                teamId, role, tenantId, e.getMessage());
        return Optional.empty();
    }
}
