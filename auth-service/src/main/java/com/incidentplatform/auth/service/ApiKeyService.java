package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.ApiKey;
import com.incidentplatform.auth.domain.ApiKeyScope;
import com.incidentplatform.auth.domain.ApiKeyType;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.ApiKeyCreatedResponse;
import com.incidentplatform.auth.dto.ApiKeyDto;
import com.incidentplatform.auth.dto.CreateApiKeyRequest;
import com.incidentplatform.auth.dto.RevokedApiKeysResponse;
import com.incidentplatform.auth.ratelimit.ApiKeyCreationLimit;
import com.incidentplatform.auth.ratelimit.RateLimitDecision;
import com.incidentplatform.auth.ratelimit.RateLimitRefusedException;
import com.incidentplatform.auth.repository.ApiKeyRepository;
import com.incidentplatform.auth.repository.IntegrationRepository;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    private static final int MAX_KEYS_PER_TENANT = 50;
    private static final int MAX_KEYS_PER_USER   = 10;

    /**
     * Audit metadata key: how many personal API keys an action revoked
     * (backlog #0-89). Set on the action's own event (password reset, MFA
     * reset, archive) rather than on a separate one, so the count is
     * recorded with the action, including the break-glass reset's
     * confirmed event.
     */
    public static final String AUDIT_PERSONAL_KEYS_REVOKED = "personalApiKeysRevoked";

    private final ApiKeyRepository apiKeyRepository;
    private final UserRepository userRepository;
    private final ApiKeyHasher apiKeyHasher;
    private final AuditEventPublisher auditEventPublisher;
    private final AuthEmailRequestService authEmailRequestService;
    private final IntegrationRepository integrationRepository;
    private final ApiKeyCreationLimit creationLimit;
    private final AfterCommit afterCommit;

    public ApiKeyService(ApiKeyRepository apiKeyRepository,
                         UserRepository userRepository,
                         ApiKeyHasher apiKeyHasher,
                         AuditEventPublisher auditEventPublisher,
                         AuthEmailRequestService authEmailRequestService,
                         IntegrationRepository integrationRepository,
                         ApiKeyCreationLimit creationLimit,
                         AfterCommit afterCommit) {
        this.apiKeyRepository   = apiKeyRepository;
        this.userRepository     = userRepository;
        this.apiKeyHasher       = apiKeyHasher;
        this.auditEventPublisher = auditEventPublisher;
        this.authEmailRequestService = authEmailRequestService;
        this.integrationRepository = integrationRepository;
        this.creationLimit = creationLimit;
        this.afterCommit = afterCommit;
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
     *
     * <h2>The creator is emailed (backlog #0-89)</h2>
     * A personal key notifies its owner, a tenant key the admin who created
     * it, through the auth email outbox in this transaction (the #0-83
     * pattern): a key created with a stolen password is the one credential
     * that outlives a lost session, so its real owner should hear of it.
     * The creator is always a user with a login session, since an API key
     * cannot reach this endpoint ({@code ApiKeyAccess}).
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

        // ── The creator, row-locked (backlog #0-89, review): the caps and the
        // hourly limit below read counts and then insert, so parallel
        // requests of one user cannot all pass; NOWAIT: a busy row is a 429 ─
        final User creator = ApiKeyCreationLimit.lockingCreator(() -> userRepository
                .findByIdAndTenantIdForUpdate(principal.userId(), tenantId))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User", principal.userId()));

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

        // ── Creation limit (backlog #0-89): one email per key, so the
        // number of keys a user may create per hour bounds the emails ──────
        final RateLimitDecision limit = creationLimit.check(tenantId, principal.userId());
        if (!limit.allowed()) {
            throw new RateLimitRefusedException(limit);
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
            apiKey = ApiKey.createPersonal(
                    tenantId, request.name(), keyHash, prefix,
                    scopeNames, request.expiresAt(), creator);
        }
        // Backlog #0-89: who made it, from which login, for a later clean-up.
        apiKey.recordCreator(creator.getId(), principal.sessionId());

        final ApiKey saved = apiKeyRepository.save(apiKey);
        authEmailRequestService.requestApiKeyCreatedNotification(creator, saved.getId());

        // After commit (backlog #0-89, review): not while the creator's row is locked.
        afterCommit.run(() -> auditEventPublisher.publishAuth(
                    principal.userId(), tenantId,
                    AuditEventTypes.API_KEY_CREATED,
                    "auth-service",
                    principal.userId().toString(),
                    "API key created: " + request.name(),
                    Map.of("keyId", saved.getId().toString(),
                            "keyType", request.keyType().name(),
                            "scopes", String.join(",", scopeNames))));

        log.info("API key created: keyId={}, name={}, type={}, tenant={}, by={}",
                saved.getId(), request.name(), request.keyType(),
                tenantId, principal.userId());

        return ApiKeyCreatedResponse.from(saved, rawKey);
    }

    // ── List ──────────────────────────────────────────────────────────────

    /**
     * Lists active keys. An admin may narrow the list to the keys one user
     * created ({@code createdBy}, backlog #0-89), to review what a taken-over
     * account made before revoking it.
     */
    @Transactional(readOnly = true)
    public List<ApiKeyDto> listApiKeys(UserPrincipal principal, UUID createdBy) {
        final String tenantId = TenantContext.get();

        if (principal.hasRole("ROLE_ADMIN")) {
            // Admins see all active keys in the tenant
            return (createdBy == null
                    ? apiKeyRepository.findActiveByTenantId(tenantId)
                    : apiKeyRepository.findActiveCreatedBy(tenantId, createdBy, Instant.EPOCH))
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

        // After commit, on the audit pool, like the other key audits (backlog #0-89, review).
        afterCommit.run(() -> auditEventPublisher.publishAuth(
                principal.userId(), tenantId,
                AuditEventTypes.API_KEY_REVOKED,
                "auth-service",
                principal.userId().toString(),
                "API key revoked: " + apiKey.getName(),
                Map.of("keyId", keyId.toString(),
                        "keyType", apiKey.getKeyType().name())));

        log.info("API key revoked: keyId={}, name={}, type={}, tenant={}, by={}",
                keyId, apiKey.getName(), apiKey.getKeyType(),
                tenantId, principal.userId());
    }

    // ── Revoke the keys a user created (account recovery) ─────────────────

    /**
     * Revokes every active key of the tenant that a user created at or after
     * {@code since} (every key they created when it is null), of every type:
     * the tenant and integration keys a taken-over admin account made survive
     * the owner's password or MFA reset, which revoke only personal keys
     * (backlog #0-89, found in review). An integration is revoked with its key.
     *
     * <p>An integration revoked this way is audited as {@code INTEGRATION_REVOKED}
     * too, as when it is revoked on its own, so a search for that type finds it.
     * A revoked integration key may still be accepted by ingestion-service for
     * up to its introspection cache (60 s, backlog #0-16).
     *
     * <p>Rules as for revoking one key ({@link #revokeApiKey}): an admin, from a
     * login (an API key reaches no route of this service but the team routes),
     * no step-up and no rate limit (user's decision, 2026-10-02): revoking is
     * the safe direction, an admin can already revoke every key one by one,
     * and a tenant without MFA must be able to clean up too. Any user of the
     * tenant, archived ones and the caller included. Audited once, listing the
     * keys and integrations revoked; a call that revoked nothing is only logged.
     *
     * @throws ResourceNotFoundException if the user is not in the caller's tenant
     */
    @Transactional
    public RevokedApiKeysResponse revokeKeysCreatedBy(UUID userId, Instant since, UserPrincipal admin) {
        final String tenantId = TenantContext.get();
        userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        final RevokedApiKeysResponse revoked = revokeCreatedBy(userId, tenantId, since, admin.userId());
        if (revoked.count() == 0) {
            // Nothing changed, so no audit event (review: an unlimited call that
            // queued an event every time could flood the shared audit queue and
            // drop other tenants' events). The log keeps the trace.
            log.info("Revoke of keys created by a user found none: createdBy={}, since={}, tenant={}, by={}",
                    userId, since, tenantId, admin.userId());
            return revoked;
        }

        // The actor first, as for one revoked key and INTEGRATION_REVOKED
        // (review: a search by user found these under different users).
        // After commit, on the audit executor, as the creations (review).
        final Map<String, Object> metadata = Map.of("createdBy", userId.toString(),
                "since", since == null ? "any" : since.toString(),
                "count", String.valueOf(revoked.count()),
                "keyIds", joined(revoked.revokedKeyIds()),
                "integrationIds", joined(revoked.revokedIntegrationIds()));
        afterCommit.run(() -> auditEventPublisher.publishAuth(
                admin.userId(), tenantId,
                AuditEventTypes.API_KEY_REVOKED,
                "auth-service",
                admin.userId().toString(),
                "API keys created by a user revoked",
                metadata));

        log.warn("API keys created by a user revoked: createdBy={}, since={}, keys={}, integrations={}, "
                        + "tenant={}, by={}", userId, since, revoked.count(),
                revoked.revokedIntegrationIds().size(), tenantId, admin.userId());
        return revoked;
    }

    /**
     * The revocation itself, for callers that audit it in their own event (the
     * admin MFA reset, which also lists the ids). Every integration revoked is
     * audited here as {@code INTEGRATION_REVOKED}, so both paths leave the same
     * trace (review: the reset's path had none). Loads the keys rather than a
     * bulk update, so an
     * integration key revokes its integration too. Bounded by the tenant's
     * active keys: at most {@value #MAX_KEYS_PER_TENANT} created through this
     * service (personal keys included), plus its integrations' keys, which
     * {@code IntegrationService} creates without that cap; one integration
     * lookup per integration key, all on a rare admin action.
     */
    @Transactional
    public RevokedApiKeysResponse revokeCreatedBy(UUID userId, String tenantId, Instant since, UUID actorId) {
        final List<UUID> keyIds = new ArrayList<>();
        final List<UUID> integrationIds = new ArrayList<>();
        for (final ApiKey key : apiKeyRepository.findActiveCreatedBy(
                tenantId, userId, since == null ? Instant.EPOCH : since)) {
            if (key.getIntegrationId() != null) {
                integrationRepository.findByIdAndTenantId(key.getIntegrationId(), tenantId)
                        .filter(integration -> !integration.isRevoked())
                        .ifPresent(integration -> {
                            integration.revoke();
                            integrationIds.add(integration.getId());
                        });
            }
            key.revoke();
            keyIds.add(key.getId());
        }
        for (final UUID integrationId : integrationIds) {
            afterCommit.run(() -> auditEventPublisher.publishAuth(
                    actorId, tenantId,
                    AuditEventTypes.INTEGRATION_REVOKED,
                    "auth-service",
                    actorId.toString(),
                    "Integration revoked with the keys its creator made",
                    Map.of("integrationId", integrationId.toString(),
                            "createdBy", userId.toString())));
        }
        return new RevokedApiKeysResponse(List.copyOf(keyIds), List.copyOf(integrationIds));
    }

    /** Comma-separated ids for audit metadata. */
    public static String joinedIds(List<UUID> ids) {
        return joined(ids);
    }

    /**
     * How many active tenant and integration keys a user created (backlog
     * #0-89): they outlive the user's archive and resets on purpose, so the
     * callers record or tell the number, pointing at
     * {@link #revokeKeysCreatedBy} when a review is needed.
     */
    @Transactional(readOnly = true)
    public long countActiveUnownedCreatedBy(String tenantId, UUID userId) {
        return apiKeyRepository.countActiveUnownedCreatedBy(tenantId, userId);
    }

    private static String joined(List<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(","));
    }

    // ── Revoke all personal keys (archive, account recovery) ──────────────

    /**
     * Revokes all PERSONAL API keys for a user.
     *
     * <p>Called when the user is archived or anonymized, and since backlog
     * #0-89 whenever the account is recovered: a password reset, an admin or
     * break-glass MFA reset, and a password change that asks for it. A key
     * created by someone who had the password for a while would otherwise
     * outlive the recovery, as nothing else ends it. The caller records the
     * count in its own audit event ({@link #AUDIT_PERSONAL_KEYS_REVOKED}).
     *
     * <p>A bulk update that clears the persistence context: call it after
     * the last change to an entity the caller still holds.
     *
     * @return the number of keys revoked
     */
    @Transactional
    public int revokeAllPersonalKeysForUser(UUID userId, String tenantId) {
        final int revoked = apiKeyRepository.revokeAllPersonalKeysForUser(userId, Instant.now());
        log.info("Personal API keys revoked for user: userId={}, tenant={}, count={}",
                userId, tenantId, revoked);
        return revoked;
    }
}
