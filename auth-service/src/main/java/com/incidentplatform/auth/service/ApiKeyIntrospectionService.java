package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.ApiKey;
import com.incidentplatform.auth.domain.Integration;
import com.incidentplatform.auth.dto.ApiKeyIntrospectionResponse;
import com.incidentplatform.auth.repository.ApiKeyRepository;
import com.incidentplatform.auth.repository.IntegrationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Resolves an API key by its SHA-256 hash: the single place that decides
 * whether a key is active and which team it routes to.
 *
 * <p>Added for backlog #0-16. Two callers share it so that "is this key valid"
 * cannot mean two different things:
 * <ul>
 *   <li>{@link ApiKeyLookupServiceImpl} — API keys sent to auth-service itself;</li>
 *   <li>{@link #introspect} — ingestion-service asking which tenant an
 *       Integration key belongs to (the {@code POST
 *       /api/v1/internal/api-keys/introspect} endpoint).</li>
 * </ul>
 *
 * <h2>Only TENANT keys introspect as active</h2>
 * {@link #introspect} answers {@code active:false} for a {@code PERSONAL} key,
 * whatever its scopes. Alert sources are machine integrations an ADMIN creates
 * and revokes centrally (the #0-16 model: key → tenant and team). A personal
 * key would let its owner exceed their role: ingest with a JWT needs
 * {@code INGESTOR} or {@code ADMIN}, but a {@code RESPONDER} may create a
 * personal key with {@code alerts:ingest} ({@code ApiKeyScope.allowedForRole},
 * backlog #0-46). It would also carry no team and put the key, not its owner,
 * in the principal. Before #0-16 ingestion-service rejected every key, so this
 * restriction keeps what was reachable unchanged. {@link #resolve} still
 * accepts personal keys: auth-service's own endpoints build the principal from
 * the owner's roles.
 */
@Service
public class ApiKeyIntrospectionService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyIntrospectionService.class);

    /** An active key and the team of its Integration ({@code null} if none). */
    public record ActiveApiKey(ApiKey apiKey, UUID teamId) { }

    private final ApiKeyRepository apiKeyRepository;
    private final IntegrationRepository integrationRepository;
    private final ApiKeyUsageRecorder usageRecorder;

    public ApiKeyIntrospectionService(ApiKeyRepository apiKeyRepository,
                                      IntegrationRepository integrationRepository,
                                      ApiKeyUsageRecorder usageRecorder) {
        this.apiKeyRepository = apiKeyRepository;
        this.integrationRepository = integrationRepository;
        this.usageRecorder = usageRecorder;
    }

    /**
     * Finds an active (not revoked, not expired) key by hash and records its
     * use. Empty for an unknown, revoked or expired key — deliberately without
     * saying which (RFC 7662 §2.2).
     */
    @Transactional(readOnly = true)
    public Optional<ActiveApiKey> resolve(String keyHash) {
        return resolve(keyHash, apiKey -> true);
    }

    /**
     * As {@link #resolve(String)}, but a key that fails {@code accepted} is
     * treated like an unknown one — before its use is recorded, so a rejected
     * key does not look used.
     */
    private Optional<ActiveApiKey> resolve(String keyHash, Predicate<ApiKey> accepted) {
        final Optional<ApiKey> keyOpt = apiKeyRepository.findActiveByHash(keyHash);
        if (keyOpt.isEmpty()) {
            log.debug("API key not found or revoked (hash prefix: {}...)",
                    keyHash.substring(0, Math.min(8, keyHash.length())));
            return Optional.empty();
        }
        final ApiKey apiKey = keyOpt.get();
        if (apiKey.isExpired()) {
            log.debug("API key expired: keyId={}", apiKey.getId());
            return Optional.empty();
        }
        if (!accepted.test(apiKey)) {
            log.debug("API key not accepted for this use: keyId={}, type={}",
                    apiKey.getId(), apiKey.getKeyType());
            return Optional.empty();
        }
        usageRecorder.recordUsage(apiKey.getId());
        return Optional.of(new ActiveApiKey(apiKey, resolveTeamId(apiKey)));
    }

    /**
     * Introspection answer for ingestion-service. Everything it needs to build
     * a principal and to bound its cache ({@code expiresAt}); nothing else —
     * no key name, owner or hash. Only TENANT keys are active here (see the
     * class Javadoc); a PERSONAL key gets the same {@code active:false} as an
     * unknown one, without saying why (RFC 7662 §2.2).
     */
    @Transactional(readOnly = true)
    public ApiKeyIntrospectionResponse introspect(String keyHash) {
        return resolve(keyHash, ApiKey::isTenant)
                .map(active -> ApiKeyIntrospectionResponse.active(
                        active.apiKey().getId(),
                        active.apiKey().getTenantId(),
                        active.teamId(),
                        active.apiKey().getScopes(),
                        active.apiKey().getExpiresAt()))
                .orElseGet(ApiKeyIntrospectionResponse::inactive);
    }

    /**
     * Integration keys route to their Integration's team. A revoked
     * Integration revokes its key too ({@code Integration.revoke()}), so the
     * {@code isActive} filter is a second line, not the only one.
     */
    private UUID resolveTeamId(ApiKey apiKey) {
        if (apiKey.getIntegrationId() == null) {
            return null;
        }
        return integrationRepository.findById(apiKey.getIntegrationId())
                .filter(Integration::isActive)
                .map(i -> i.getTeam() != null ? i.getTeam().getId() : null)
                .orElse(null);
    }
}
