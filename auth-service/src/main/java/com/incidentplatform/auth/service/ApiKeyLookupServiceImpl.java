package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.ApiKey;
import com.incidentplatform.auth.domain.Integration;
import com.incidentplatform.auth.repository.ApiKeyRepository;
import com.incidentplatform.auth.repository.IntegrationRepository;
import com.incidentplatform.shared.security.ApiKeyAuthFilter;
import com.incidentplatform.shared.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * DB-backed implementation of {@link ApiKeyAuthFilter.ApiKeyLookupService}.
 *
 * <p>Called by {@link ApiKeyAuthFilter} on every API key request.
 * Looks up the key by SHA-256 hash, validates it, and builds a
 * {@link UserPrincipal} for the Spring Security context.
 *
 * <h2>Principal construction</h2>
 * <ul>
 *   <li><b>TENANT key</b> — principal has tenant-level ADMIN role
 *       (configurable) and granted scopes. No userId.</li>
 *   <li><b>PERSONAL key</b> — principal inherits owner's roles and
 *       has granted scopes. userId = owner's UUID.</li>
 * </ul>
 *
 * <h2>Usage recording</h2>
 * Delegates to {@link ApiKeyService#recordUsageAsync} — the filter path
 * must not block on a DB write. Usage recording is best-effort.
 */
@Service
public class ApiKeyLookupServiceImpl
        implements ApiKeyAuthFilter.ApiKeyLookupService {

    private static final Logger log =
            LoggerFactory.getLogger(ApiKeyLookupServiceImpl.class);

    private final ApiKeyRepository apiKeyRepository;
    private final IntegrationRepository integrationRepository;
    private final ApiKeyHasher apiKeyHasher;
    private final ApiKeyService apiKeyService;

    public ApiKeyLookupServiceImpl(ApiKeyRepository apiKeyRepository,
                                   IntegrationRepository integrationRepository,
                                   ApiKeyHasher apiKeyHasher,
                                   ApiKeyService apiKeyService) {
        this.apiKeyRepository      = apiKeyRepository;
        this.integrationRepository = integrationRepository;
        this.apiKeyHasher          = apiKeyHasher;
        this.apiKeyService         = apiKeyService;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserPrincipal> lookup(String rawKey) {
        final String hash = apiKeyHasher.hash(rawKey);

        final Optional<ApiKey> keyOpt = apiKeyRepository.findActiveByHash(hash);

        if (keyOpt.isEmpty()) {
            log.debug("API key not found or revoked (hash prefix: {}...)",
                    hash.substring(0, 8));
            return Optional.empty();
        }

        final ApiKey apiKey = keyOpt.get();

        if (apiKey.isExpired()) {
            log.debug("API key expired: keyId={}", apiKey.getId());
            return Optional.empty();
        }

        // Record usage asynchronously — best-effort, non-blocking
        apiKeyService.recordUsageAsync(apiKey.getId());

        final UserPrincipal principal = buildPrincipal(apiKey);
        return Optional.of(principal);
    }

    private UserPrincipal buildPrincipal(ApiKey apiKey) {
        final List<String> roles;
        final UUID userId;
        // teamId — resolved from Integration for routing alerts to correct team
        final UUID teamId = resolveTeamId(apiKey);

        if (apiKey.isTenant()) {
            roles  = List.of("ROLE_RESPONDER");
            userId = apiKey.getId();
        } else {
            roles  = apiKey.getOwnerUser().getRoleNames();
            userId = apiKey.getOwnerUser().getId();
        }

        // teamId is stored in teamIds list — ingestion-service reads
        // principal.teamIds().get(0) to set UnifiedAlertDto.teamId
        final List<UUID> teamIds = teamId != null
                ? List.of(teamId) : List.of();

        return new UserPrincipal(
                userId,
                apiKey.getTenantId(),
                apiKey.isTenant()
                        ? "api-key:" + apiKey.getName()
                        : apiKey.getOwnerUser().getEmail(),
                roles,
                teamIds,
                true,
                apiKey.getScopes()
        );
    }

    /**
     * Resolves the teamId for an API key that belongs to an Integration.
     *
     * <p>Integration keys have {@code integrationId} set — one JOIN fetches
     * the team. Personal keys and manually-created Tenant keys return null.
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