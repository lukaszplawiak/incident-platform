package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.ApiKey;
import com.incidentplatform.auth.domain.ApiKeyScope;
import com.incidentplatform.auth.domain.ApiKeyType;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.ApiKeyCreatedResponse;
import com.incidentplatform.auth.dto.ApiKeyDto;
import com.incidentplatform.auth.dto.CreateApiKeyRequest;
import com.incidentplatform.auth.repository.ApiKeyRepository;
import com.incidentplatform.auth.repository.UserRepository;
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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    private static final int MAX_KEYS_PER_TENANT = 50;
    private static final int MAX_KEYS_PER_USER   = 10;

    private final ApiKeyRepository apiKeyRepository;
    private final UserRepository userRepository;
    private final ApiKeyHasher apiKeyHasher;
    private final AuditEventPublisher auditEventPublisher;

    public ApiKeyService(ApiKeyRepository apiKeyRepository,
                         UserRepository userRepository,
                         ApiKeyHasher apiKeyHasher,
                         AuditEventPublisher auditEventPublisher) {
        this.apiKeyRepository   = apiKeyRepository;
        this.userRepository     = userRepository;
        this.apiKeyHasher       = apiKeyHasher;
        this.auditEventPublisher = auditEventPublisher;
    }

    // ── Create ────────────────────────────────────────────────────────────

    /**
     * Creates a new API key.
     *
     * <p>Business rules:
     * <ul>
     *   <li>TENANT keys: only ADMIN can create</li>
     *   <li>PERSONAL keys: any authenticated user for themselves</li>
     *   <li>PERSONAL key scopes cannot exceed owner's roles</li>
     *   <li>expiresAt must be in the future if provided</li>
     *   <li>Rate limits: max {@value #MAX_KEYS_PER_TENANT} per tenant,
     *       {@value #MAX_KEYS_PER_USER} per user (personal)</li>
     * </ul>
     *
     * <p>The raw key is returned ONCE in {@link ApiKeyCreatedResponse#rawKey()}.
     * It is not stored — only the SHA-256 hash is persisted.
     */
    @Transactional
    public ApiKeyCreatedResponse createApiKey(CreateApiKeyRequest request,
                                              UserPrincipal principal) {
        final String tenantId = TenantContext.get();

        // ── Type-specific guards ──────────────────────────────────────────
        if (request.keyType() == ApiKeyType.TENANT
                && !principal.hasRole("ROLE_ADMIN")) {
            throw new BusinessException(
                    ErrorCodes.FORBIDDEN,
                    "Only ADMIN users can create TENANT API keys",
                    HttpStatus.FORBIDDEN);
        }

        // ── Expiry validation ─────────────────────────────────────────────
        if (request.expiresAt() != null && !request.expiresAt().isAfter(Instant.now())) {
            throw new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "expiresAt must be in the future",
                    HttpStatus.BAD_REQUEST);
        }

        // ── Rate limits ───────────────────────────────────────────────────
        final List<ApiKey> existingKeys =
                apiKeyRepository.findActiveByTenantId(tenantId);

        if (existingKeys.size() >= MAX_KEYS_PER_TENANT) {
            throw new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "Maximum of " + MAX_KEYS_PER_TENANT +
                            " active API keys per tenant reached. Revoke unused keys first.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        if (request.keyType() == ApiKeyType.PERSONAL) {
            final long personalCount = existingKeys.stream()
                    .filter(k -> k.isPersonal()
                            && k.getOwnerUser() != null
                            && k.getOwnerUser().getId().equals(principal.userId()))
                    .count();
            if (personalCount >= MAX_KEYS_PER_USER) {
                throw new BusinessException(
                        ErrorCodes.BUSINESS_RULE_VIOLATION,
                        "Maximum of " + MAX_KEYS_PER_USER +
                                " personal API keys per user reached.",
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
        }

        // ── Scope validation ──────────────────────────────────────────────
        final List<String> scopeNames = request.scopes().stream()
                .map(ApiKeyScope::getScopeName)
                .toList();

        if (request.keyType() == ApiKeyType.PERSONAL) {
            for (final ApiKeyScope scope : request.scopes()) {
                final boolean allowed = principal.roles().stream()
                        .anyMatch(scope::allowedForRole);
                if (!allowed) {
                    throw new BusinessException(
                            ErrorCodes.FORBIDDEN,
                            "Scope '" + scope.getScopeName() +
                                    "' exceeds your role permissions",
                            HttpStatus.FORBIDDEN);
                }
            }
        }

        // ── Generate key ──────────────────────────────────────────────────
        final String rawKey  = apiKeyHasher.generateRawKey();
        final String keyHash = apiKeyHasher.hash(rawKey);
        final String prefix  = apiKeyHasher.extractPrefix(rawKey);

        // ── Persist ───────────────────────────────────────────────────────
        final ApiKey apiKey;
        if (request.keyType() == ApiKeyType.TENANT) {
            apiKey = ApiKey.createTenant(
                    tenantId, request.name(), keyHash, prefix,
                    scopeNames, request.expiresAt());
        } else {
            final User owner = userRepository
                    .findByIdAndTenantId(principal.userId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "User", principal.userId()));
            apiKey = ApiKey.createPersonal(
                    tenantId, request.name(), keyHash, prefix,
                    scopeNames, request.expiresAt(), owner);
        }

        final ApiKey saved = apiKeyRepository.save(apiKey);

        auditEventPublisher.publishAuth(
                principal.userId(), tenantId,
                AuditEventTypes.API_KEY_CREATED,
                "auth-service",
                principal.userId().toString(),
                "API key created: " + request.name(),
                Map.of("keyId", saved.getId().toString(),
                        "keyType", request.keyType().name(),
                        "scopes", String.join(",", scopeNames)));

        log.info("API key created: keyId={}, name={}, type={}, tenant={}, by={}",
                saved.getId(), request.name(), request.keyType(),
                tenantId, principal.userId());

        return ApiKeyCreatedResponse.from(saved, rawKey);
    }

    // ── List ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ApiKeyDto> listApiKeys(UserPrincipal principal) {
        final String tenantId = TenantContext.get();

        if (principal.hasRole("ROLE_ADMIN")) {
            // Admins see all active keys in the tenant
            return apiKeyRepository.findActiveByTenantId(tenantId)
                    .stream()
                    .map(ApiKeyDto::from)
                    .toList();
        }

        // Non-admins see only their personal keys
        return apiKeyRepository.findActiveByOwnerId(principal.userId())
                .stream()
                .map(ApiKeyDto::from)
                .toList();
    }

    // ── Revoke ────────────────────────────────────────────────────────────

    /**
     * Revokes an API key.
     *
     * <p>Rules:
     * <ul>
     *   <li>ADMIN can revoke any key in the tenant</li>
     *   <li>RESPONDER can only revoke their own PERSONAL keys</li>
     * </ul>
     */
    @Transactional
    public void revokeApiKey(UUID keyId, UserPrincipal principal) {
        final String tenantId = TenantContext.get();

        final ApiKey apiKey = apiKeyRepository.findByIdAndTenantId(keyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("ApiKey", keyId));

        if (apiKey.isRevoked()) {
            throw new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "API key is already revoked",
                    HttpStatus.CONFLICT);
        }

        if (!principal.hasRole("ROLE_ADMIN")) {
            // Non-admins can only revoke their own personal keys
            if (!apiKey.isPersonal()
                    || apiKey.getOwnerUser() == null
                    || !apiKey.getOwnerUser().getId().equals(principal.userId())) {
                throw new BusinessException(
                        ErrorCodes.FORBIDDEN,
                        "You can only revoke your own personal API keys",
                        HttpStatus.FORBIDDEN);
            }
        }

        apiKey.revoke();
        apiKeyRepository.save(apiKey);

        auditEventPublisher.publishAuth(
                principal.userId(), tenantId,
                AuditEventTypes.API_KEY_REVOKED,
                "auth-service",
                principal.userId().toString(),
                "API key revoked: " + apiKey.getName(),
                Map.of("keyId", keyId.toString(),
                        "keyType", apiKey.getKeyType().name()));

        log.info("API key revoked: keyId={}, name={}, type={}, tenant={}, by={}",
                keyId, apiKey.getName(), apiKey.getKeyType(),
                tenantId, principal.userId());
    }

    // ── Revoke all personal keys (user archive/anonymize) ─────────────────

    /**
     * Revokes all PERSONAL API keys for a user.
     * Called by {@code UserManagementService.archiveUser()} and
     * {@code UserManagementService.anonymizeUser()}.
     */
    @Transactional
    public void revokeAllPersonalKeysForUser(UUID userId, String tenantId) {
        final int count = 0;
        apiKeyRepository.revokeAllPersonalKeysForUser(userId, Instant.now());
        log.info("All personal API keys revoked for user: userId={}, tenant={}",
                userId, tenantId);
    }

    // ── Record usage (async) ──────────────────────────────────────────────

    /**
     * Updates {@code last_used_at} asynchronously — called after every
     * successful API key authentication.
     *
     * <p>Async to avoid adding a synchronous DB write to every hot request.
     * Best-effort: if this fails, the key still works — we just lose
     * the usage timestamp precision.
     */
    @Async
    @Transactional
    public void recordUsageAsync(UUID keyId) {
        apiKeyRepository.findById(keyId).ifPresent(key -> {
            key.recordUsage();
            apiKeyRepository.save(key);
        });
    }
}