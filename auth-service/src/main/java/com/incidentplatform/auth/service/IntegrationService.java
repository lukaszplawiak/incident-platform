package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.ApiKey;
import com.incidentplatform.auth.domain.ApiKeyScope;
import com.incidentplatform.auth.domain.Integration;
import com.incidentplatform.auth.domain.Team;
import com.incidentplatform.auth.dto.CreateIntegrationRequest;
import com.incidentplatform.auth.dto.IntegrationCreatedResponse;
import com.incidentplatform.auth.dto.IntegrationDto;
import com.incidentplatform.auth.repository.ApiKeyRepository;
import com.incidentplatform.auth.repository.IntegrationRepository;
import com.incidentplatform.auth.repository.TeamRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class IntegrationService {

    private static final Logger log =
            LoggerFactory.getLogger(IntegrationService.class);

    private final IntegrationRepository integrationRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final TeamRepository teamRepository;
    private final ApiKeyHasher apiKeyHasher;
    private final AuditEventPublisher auditEventPublisher;

    public IntegrationService(IntegrationRepository integrationRepository,
                              ApiKeyRepository apiKeyRepository,
                              TeamRepository teamRepository,
                              ApiKeyHasher apiKeyHasher,
                              AuditEventPublisher auditEventPublisher) {
        this.integrationRepository = integrationRepository;
        this.apiKeyRepository      = apiKeyRepository;
        this.teamRepository        = teamRepository;
        this.apiKeyHasher          = apiKeyHasher;
        this.auditEventPublisher   = auditEventPublisher;
    }

    // ── Create ────────────────────────────────────────────────────────────

    /**
     * Creates a new Integration with a dedicated API key.
     *
     * <p>Flow:
     * <ol>
     *   <li>Validate name uniqueness within tenant</li>
     *   <li>Resolve team (optional)</li>
     *   <li>Generate API key (TENANT type, alerts:ingest scope)</li>
     *   <li>Persist ApiKey first (Integration references it)</li>
     *   <li>Persist Integration</li>
     *   <li>Back-fill integration_id on ApiKey</li>
     * </ol>
     *
     * <p>The raw API key is returned once in {@link IntegrationCreatedResponse#apiKey()}.
     * It is not stored — only the SHA-256 hash persists.
     */
    @Transactional
    public IntegrationCreatedResponse createIntegration(
            CreateIntegrationRequest request, UserPrincipal principal) {

        final String tenantId = TenantContext.get();

        // ── Name uniqueness ───────────────────────────────────────────────
        if (integrationRepository.existsByNameAndTenantId(request.name(), tenantId)) {
            throw new BusinessException(
                    ErrorCodes.ALREADY_EXISTS,
                    "Integration with name '" + request.name() +
                            "' already exists in this tenant",
                    HttpStatus.CONFLICT);
        }

        // ── Resolve team ──────────────────────────────────────────────────
        Team team = null;
        if (request.teamId() != null) {
            team = teamRepository.findByIdAndTenantId(request.teamId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Team", request.teamId()));
        } else {
            log.warn("Integration created without team assignment: name={}, tenant={}. " +
                            "Escalation will skip on-call routing until a team is assigned.",
                    request.name(), tenantId);
        }

        // ── Generate API key ──────────────────────────────────────────────
        // TENANT type — not bound to a specific user, alerts:ingest scope only.
        // Naming convention: "Integration: <integration name>" for audit trail.
        final String rawKey  = apiKeyHasher.generateRawKey();
        final String keyHash = apiKeyHasher.hash(rawKey);
        final String prefix  = apiKeyHasher.extractPrefix(rawKey);

        final ApiKey apiKey = ApiKey.createTenant(
                tenantId,
                "Integration: " + request.name(),
                keyHash,
                prefix,
                List.of(ApiKeyScope.ALERTS_INGEST.getScopeName()),
                null  // non-expiring — integration keys don't expire automatically
        );

        // Persist ApiKey first — Integration FK references api_keys.id
        final ApiKey savedApiKey = apiKeyRepository.save(apiKey);

        // ── Persist Integration ───────────────────────────────────────────
        final Integration integration = Integration.create(
                tenantId, request.name(), request.source(),
                team, savedApiKey, request.description());

        final Integration saved = integrationRepository.save(integration);

        // ── Back-fill integration_id on ApiKey ────────────────────────────
        // Creates the bidirectional link: ApiKey knows which Integration owns it.
        // Used by ApiKeyLookupServiceImpl to fetch teamId in a single JOIN.
        savedApiKey.setIntegrationId(saved.getId());
        apiKeyRepository.save(savedApiKey);

        auditEventPublisher.publishAuth(
                principal.userId(), tenantId,
                AuditEventTypes.INTEGRATION_CREATED,
                "auth-service",
                principal.userId().toString(),
                "Integration created: " + request.name(),
                Map.of("integrationId", saved.getId().toString(),
                        "source", request.source(),
                        "teamId", team != null ? team.getId().toString() : "none"));

        log.info("Integration created: id={}, name={}, source={}, teamId={}, tenant={}",
                saved.getId(), request.name(), request.source(),
                team != null ? team.getId() : null, tenantId);

        return IntegrationCreatedResponse.from(saved, rawKey);
    }

    // ── List ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<IntegrationDto> listIntegrations() {
        return integrationRepository
                .findActiveByTenantId(TenantContext.get())
                .stream()
                .map(IntegrationDto::from)
                .toList();
    }

    // ── Revoke ────────────────────────────────────────────────────────────

    /**
     * Revokes an Integration and its API key.
     *
     * <p>After revocation:
     * <ul>
     *   <li>Requests using the integration's API key return 401 immediately</li>
     *   <li>The integration and key remain in the DB for audit purposes</li>
     *   <li>The external monitoring system will start failing to ingest alerts</li>
     * </ul>
     */
    @Transactional
    public void revokeIntegration(UUID integrationId, UserPrincipal principal) {
        final String tenantId = TenantContext.get();

        final Integration integration = integrationRepository
                .findByIdAndTenantId(integrationId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Integration", integrationId));

        if (integration.isRevoked()) {
            throw new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "Integration is already revoked",
                    HttpStatus.CONFLICT);
        }

        // Revokes integration + cascades to its ApiKey
        integration.revoke();
        integrationRepository.save(integration);

        auditEventPublisher.publishAuth(
                principal.userId(), tenantId,
                AuditEventTypes.INTEGRATION_REVOKED,
                "auth-service",
                principal.userId().toString(),
                "Integration revoked: " + integration.getName(),
                Map.of("integrationId", integrationId.toString(),
                        "source", integration.getSource()));

        log.info("Integration revoked: id={}, name={}, tenant={}, by={}",
                integrationId, integration.getName(),
                tenantId, principal.userId());
    }
}