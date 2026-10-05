package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.ApiKey;
import com.incidentplatform.auth.domain.Integration;
import com.incidentplatform.auth.dto.ApiKeyIntrospectionResponse;
import com.incidentplatform.auth.repository.ApiKeyRepository;
import com.incidentplatform.auth.repository.IntegrationRepository;
import com.incidentplatform.shared.security.TenantAccess;
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
    private final TenantAccessService tenantAccessService;

    public ApiKeyIntrospectionService(ApiKeyRepository apiKeyRepository,
                                      IntegrationRepository integrationRepository,
                                      ApiKeyUsageRecorder usageRecorder,
                                      TenantAccessService tenantAccessService) {
        this.tenantAccessService = tenantAccessService;
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
        return resolve(keyHash, apiKey -> true, false) instanceof Resolution.Active(ActiveApiKey active)
                ? Optional.of(active)
                : Optional.empty();
    }

    /**
     * What {@link #resolve(String, Predicate, boolean)} decided: the key may be
     * used, it may not, or it may not <em>yet</em> — a TENANT key of a
     * read-only tenant asked for a write (backlog #0-82).
     */
    private sealed interface Resolution {
        record Active(ActiveApiKey key) implements Resolution { }
        record Refused() implements Resolution { }
        record Paused() implements Resolution { }
    }

    /**
     * As {@link #resolve(String)}, but a key that fails {@code accepted} is
     * treated like an unknown one — before its use is recorded, so a rejected
     * key does not look used.
     *
     * <p>Backlog #0-82: a key of a tenant suspended in full is treated like an
     * unknown one, and so is a personal key whose owner is deactivated (until
     * then such a key kept working, though its owner could not log in). A
     * suspension does not revoke keys: they work again once the tenant is
     * resumed. {@code writes}: the caller acts with the key only to write (an
     * alert into ingestion-service); a read-only tenant's key that passes every
     * other check is then {@link Resolution.Paused}, not refused — the work is
     * paused, not dropped, so the sender is told to come back later.
     */
    private Resolution resolve(String keyHash, Predicate<ApiKey> accepted, boolean writes) {
        final Optional<ApiKey> keyOpt = apiKeyRepository.findActiveByHash(keyHash);
        if (keyOpt.isEmpty()) {
            log.debug("API key not found or revoked (hash prefix: {}...)",
                    keyHash.substring(0, Math.min(8, keyHash.length())));
            return new Resolution.Refused();
        }
        final ApiKey apiKey = keyOpt.get();
        if (apiKey.isExpired()) {
            log.debug("API key expired: keyId={}", apiKey.getId());
            return new Resolution.Refused();
        }
        final TenantAccess access = tenantAccessService.accessOf(apiKey.getTenantId());
        if (access == TenantAccess.NONE) {
            log.debug("API key of a suspended tenant refused: keyId={}, tenant={}",
                    apiKey.getId(), apiKey.getTenantId());
            return new Resolution.Refused();
        }
        if (!apiKey.isTenant() && apiKey.getOwnerUser() != null && !apiKey.getOwnerUser().isActive()) {
            log.debug("Personal API key of a deactivated user refused: keyId={}", apiKey.getId());
            return new Resolution.Refused();
        }
        if (!accepted.test(apiKey)) {
            log.debug("API key not accepted for this use: keyId={}, type={}",
                    apiKey.getId(), apiKey.getKeyType());
            return new Resolution.Refused();
        }
        // After `accepted`: a key that would be refused anyway is not told
        // its tenant is paused — that would say the key is otherwise valid.
        if (writes && access == TenantAccess.READ_ONLY) {
            log.debug("API key of a read-only tenant paused for a write: keyId={}, tenant={}",
                    apiKey.getId(), apiKey.getTenantId());
            return new Resolution.Paused();
        }
        usageRecorder.recordUsage(apiKey.getId());
        return new Resolution.Active(new ActiveApiKey(apiKey, resolveTeamId(apiKey)));
    }

    /**
     * Introspection answer for ingestion-service. Everything it needs to build
     * a principal and to bound its cache ({@code expiresAt}); nothing else —
     * no key name, owner or hash. Only TENANT keys are active here (see the
     * class Javadoc); a PERSONAL key gets the same {@code active:false} as an
     * unknown one, without saying why (RFC 7662 §2.2).
     *
     * <p>Backlog #0-82: a valid TENANT key of a read-only tenant gets
     * {@code active:false, paused:true} — ingestion-service answers the alert
     * 503 + {@code Retry-After}, so Alertmanager keeps it and retries instead
     * of dropping it on a 401. A successful answer, not a 5xx, so ingestion's
     * circuit breaker does not count a paused tenant as auth-service failing.
     */
    @Transactional(readOnly = true)
    public ApiKeyIntrospectionResponse introspect(String keyHash) {
        // Ingestion uses the answer to file alerts: a write (backlog #0-82).
        return switch (resolve(keyHash, ApiKey::isTenant, true)) {
            case Resolution.Active(ActiveApiKey active) -> ApiKeyIntrospectionResponse.active(
                    active.apiKey().getId(),
                    active.apiKey().getTenantId(),
                    active.teamId(),
                    active.apiKey().getScopes(),
                    active.apiKey().getExpiresAt());
            case Resolution.Paused paused -> ApiKeyIntrospectionResponse.pausedForWrites();
            case Resolution.Refused refused -> ApiKeyIntrospectionResponse.inactive();
        };
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
