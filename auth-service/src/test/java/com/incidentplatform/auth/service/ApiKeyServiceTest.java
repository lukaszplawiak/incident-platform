package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.ApiKey;
import com.incidentplatform.auth.domain.ApiKeyScope;
import com.incidentplatform.auth.domain.ApiKeyType;
import com.incidentplatform.auth.domain.Integration;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.ApiKeyCreatedResponse;
import com.incidentplatform.auth.dto.ApiKeyDto;
import com.incidentplatform.auth.dto.CreateApiKeyRequest;
import com.incidentplatform.auth.ratelimit.ApiKeyCreationLimit;
import com.incidentplatform.auth.ratelimit.RateLimitDecision;
import com.incidentplatform.auth.ratelimit.RateLimitRefusedException;
import com.incidentplatform.auth.repository.ApiKeyRepository;
import com.incidentplatform.auth.repository.IntegrationRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UserPrincipal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyService")
class ApiKeyServiceTest {

    @Mock private ApiKeyRepository apiKeyRepository;
    @Mock private UserRepository userRepository;
    @Mock private ApiKeyHasher apiKeyHasher;
    @Mock private AuditEventPublisher auditEventPublisher;
    @Mock private AuthEmailRequestService authEmailRequestService;
    @Mock private IntegrationRepository integrationRepository;
    @Mock private ApiKeyCreationLimit creationLimit;

    private ApiKeyService service;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID   ADMIN_ID  = UUID.randomUUID();
    private static final UUID   USER_ID   = UUID.randomUUID();
    private static final UUID   KEY_ID    = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ApiKeyService(
                apiKeyRepository, userRepository,
                apiKeyHasher, auditEventPublisher, authEmailRequestService, integrationRepository, creationLimit,
                // Runs the audit at once: no transaction here, and a direct executor.
                new AfterCommit(Runnable::run, new SimpleMeterRegistry()));
        TenantContext.set(TENANT_ID);
        lenient().when(creationLimit.check(any(), any()))
                .thenReturn(RateLimitDecision.ALLOWED);

        // lenient — not all tests call key generation (revoke/list tests skip it)
        lenient().when(apiKeyHasher.generateRawKey()).thenReturn("ipl_abcdefgh12345678901234567890123456");
        lenient().when(apiKeyHasher.hash(anyString())).thenReturn("sha256hashvalue");
        lenient().when(apiKeyHasher.extractPrefix(anyString())).thenReturn("abcdefgh");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ── createApiKey ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("createApiKey")
    class CreateApiKey {

        @Test
        @DisplayName("creates TENANT key — ADMIN only")
        void createsTenantKey() {
            final User admin = buildUser(ADMIN_ID, "ROLE_ADMIN");
            given(userRepository.findByIdAndTenantIdForUpdate(ADMIN_ID, TENANT_ID))
                    .willReturn(Optional.of(admin));
            given(apiKeyRepository.findActiveByTenantId(TENANT_ID))
                    .willReturn(List.of());
            given(apiKeyRepository.save(any())).willAnswer(i -> {
                final ApiKey saved = i.getArgument(0);
                // Simulate JPA @GeneratedValue — set UUID via reflection
                try {
                    final var field = ApiKey.class.getDeclaredField("id");
                    field.setAccessible(true);
                    field.set(saved, KEY_ID);
                } catch (Exception e) { throw new RuntimeException(e); }
                return saved;
            });

            final ApiKeyCreatedResponse response = service.createApiKey(
                    new CreateApiKeyRequest(
                            "Grafana prod", ApiKeyType.TENANT,
                            List.of(ApiKeyScope.INCIDENTS_READ), null),
                    adminPrincipal());

            assertThat(response.rawKey()).startsWith("ipl_");
            assertThat(response.keyType()).isEqualTo(ApiKeyType.TENANT);
            assertThat(response.message()).contains("not be shown again");

            final ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
            then(apiKeyRepository).should().save(captor.capture());
            assertThat(captor.getValue().isTenant()).isTrue();
            assertThat(captor.getValue().getOwnerUser()).isNull();
            // Backlog #0-89: a tenant key has no owner, so its creator is recorded.
            assertThat(captor.getValue().getCreatedByUserId()).isEqualTo(ADMIN_ID);
            assertThat(captor.getValue().getCreatedInSessionId()).isEqualTo(SESSION_ID);
            // Backlog #0-89: the admin who created it is told.
            then(authEmailRequestService).should().requestApiKeyCreatedNotification(admin, KEY_ID);
        }

        @Test
        @DisplayName("creates PERSONAL key — any authenticated user")
        void createsPersonalKey() {
            final User owner = buildUser(USER_ID, "ROLE_RESPONDER");
            given(apiKeyRepository.findActiveByTenantId(TENANT_ID))
                    .willReturn(List.of());
            given(userRepository.findByIdAndTenantIdForUpdate(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(owner));
            given(apiKeyRepository.save(any())).willAnswer(i -> {
                final ApiKey saved = i.getArgument(0);
                try {
                    final var field = ApiKey.class.getDeclaredField("id");
                    field.setAccessible(true);
                    field.set(saved, KEY_ID);
                } catch (Exception e) { throw new RuntimeException(e); }
                return saved;
            });

            final ApiKeyCreatedResponse response = service.createApiKey(
                    new CreateApiKeyRequest(
                            "My script", ApiKeyType.PERSONAL,
                            List.of(ApiKeyScope.INCIDENTS_READ), null),
                    responderPrincipal());

            assertThat(response.keyType()).isEqualTo(ApiKeyType.PERSONAL);

            final ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
            then(apiKeyRepository).should().save(captor.capture());
            assertThat(captor.getValue().isPersonal()).isTrue();
            assertThat(captor.getValue().getOwnerUser()).isEqualTo(owner);
            // Backlog #0-89: the owner is told.
            then(authEmailRequestService).should().requestApiKeyCreatedNotification(owner, KEY_ID);
        }

        @Test
        @DisplayName("throws 403 when RESPONDER tries to create TENANT key")
        void throws403WhenResponderCreatesTenantKey() {
            assertThatThrownBy(() -> service.createApiKey(
                    new CreateApiKeyRequest(
                            "Bad key", ApiKeyType.TENANT,
                            List.of(ApiKeyScope.INCIDENTS_READ), null),
                    responderPrincipal()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("throws 403 when PERSONAL key scope exceeds owner role")
        void throws403WhenScopeExceedsRole() {
            // Refused before the creator is locked or any count is read.

            assertThatThrownBy(() -> service.createApiKey(
                    new CreateApiKeyRequest(
                            "Bad scope", ApiKeyType.PERSONAL,
                            List.of(ApiKeyScope.TEAMS_WRITE), null), // ADMIN only
                    responderPrincipal()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            then(authEmailRequestService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("429 over the hourly creation limit: no key, no email (backlog #0-89)")
        void refusedByCreationLimit() {
            given(apiKeyRepository.findActiveByTenantId(TENANT_ID)).willReturn(List.of());
            final var limited = new RateLimitDecision(
                    RateLimitDecision.Outcome.LIMITED, 120);
            given(creationLimit.check(TENANT_ID, USER_ID)).willReturn(limited);
            given(userRepository.findByIdAndTenantIdForUpdate(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(buildUser(USER_ID, "ROLE_RESPONDER")));

            assertThatThrownBy(() -> service.createApiKey(
                    new CreateApiKeyRequest("Loop", ApiKeyType.PERSONAL, List.of(ApiKeyScope.TEAMS_READ), null),
                    responderPrincipal()))
                    .isInstanceOfSatisfying(RateLimitRefusedException.class,
                            e -> assertThat(e.decision()).isEqualTo(limited));
            then(apiKeyRepository).should(org.mockito.Mockito.never()).save(any());
            then(authEmailRequestService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("404 and no key, no email when the creator is not a user of the tenant (backlog #0-89)")
        void throws404WhenCreatorMissing() {
            given(userRepository.findByIdAndTenantIdForUpdate(ADMIN_ID, TENANT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.createApiKey(
                    new CreateApiKeyRequest(
                            "Orphan", ApiKeyType.TENANT,
                            List.of(ApiKeyScope.INCIDENTS_READ), null),
                    adminPrincipal()))
                    .isInstanceOf(ResourceNotFoundException.class);
            then(apiKeyRepository).should(org.mockito.Mockito.never()).save(any());
            then(authEmailRequestService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("throws 400 when expiresAt is in the past")
        void throws400WhenExpiresAtInPast() {
            // No stub needed — expiresAt validation happens before DB call
            assertThatThrownBy(() -> service.createApiKey(
                    new CreateApiKeyRequest(
                            "Expired key", ApiKeyType.TENANT,
                            List.of(ApiKeyScope.INCIDENTS_READ),
                            Instant.now().minusSeconds(3600)),
                    adminPrincipal()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("raw key is returned once and not stored")
        void rawKeyReturnedOnceNotStored() {
            given(userRepository.findByIdAndTenantIdForUpdate(ADMIN_ID, TENANT_ID))
                    .willReturn(Optional.of(buildUser(ADMIN_ID, "ROLE_ADMIN")));
            given(apiKeyRepository.findActiveByTenantId(TENANT_ID))
                    .willReturn(List.of());
            given(apiKeyRepository.save(any())).willAnswer(i -> {
                final ApiKey saved = i.getArgument(0);
                try {
                    final var field = ApiKey.class.getDeclaredField("id");
                    field.setAccessible(true);
                    field.set(saved, KEY_ID);
                } catch (Exception e) { throw new RuntimeException(e); }
                return saved;
            });

            final ApiKeyCreatedResponse response = service.createApiKey(
                    new CreateApiKeyRequest(
                            "Test", ApiKeyType.TENANT,
                            List.of(ApiKeyScope.INCIDENTS_READ), null),
                    adminPrincipal());

            // Raw key is in the response
            assertThat(response.rawKey()).isNotBlank();

            // But only hash is in the saved entity
            final ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
            then(apiKeyRepository).should().save(captor.capture());
            assertThat(captor.getValue().getKeyHash()).isEqualTo("sha256hashvalue");
        }
    }

    // ── revokeKeysCreatedBy ───────────────────────────────────────────────

    @Nested
    @DisplayName("revokeKeysCreatedBy (backlog #0-89)")
    class RevokeKeysCreatedBy {

        @Test
        @DisplayName("revokes every key the user created since the time, an integration with its key, audited once")
        void revokesKeysAndIntegrations() {
            final Instant since = Instant.parse("2026-10-01T00:00:00Z");
            final ApiKey plain = withId(buildTenantKey());
            final ApiKey integrationKey = withId(buildTenantKey());
            final UUID integrationId = UUID.randomUUID();
            integrationKey.setIntegrationId(integrationId);
            final Integration integration =
                    org.mockito.Mockito.mock(Integration.class);
            given(integration.getId()).willReturn(integrationId);
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(buildUser(USER_ID, "ROLE_ADMIN")));
            given(apiKeyRepository.findActiveCreatedBy(TENANT_ID, USER_ID, since))
                    .willReturn(List.of(plain, integrationKey));
            given(integrationRepository.findByIdAndTenantId(integrationId, TENANT_ID))
                    .willReturn(Optional.of(integration));

            final var revoked = service.revokeKeysCreatedBy(USER_ID, since, adminPrincipal());

            assertThat(revoked.revokedKeyIds()).containsExactly(plain.getId(), integrationKey.getId());
            assertThat(revoked.revokedIntegrationIds()).containsExactly(integrationId);
            assertThat(plain.isRevoked()).isTrue();
            assertThat(integrationKey.isRevoked()).isTrue();
            then(integration).should().revoke();
            // Audited as an integration revocation too, as when revoked on its own.
            then(auditEventPublisher).should().publishAuth(
                    org.mockito.ArgumentMatchers.eq(ADMIN_ID), org.mockito.ArgumentMatchers.eq(TENANT_ID),
                    org.mockito.ArgumentMatchers.eq(
                            AuditEventTypes.INTEGRATION_REVOKED),
                    anyString(), anyString(), anyString(),
                    org.mockito.ArgumentMatchers.eq(java.util.Map.of(
                            "integrationId", integrationId.toString(), "createdBy", USER_ID.toString())));
            // The actor first, as for one revoked key (review).
            then(auditEventPublisher).should().publishAuth(
                    org.mockito.ArgumentMatchers.eq(ADMIN_ID), org.mockito.ArgumentMatchers.eq(TENANT_ID),
                    org.mockito.ArgumentMatchers.eq(AuditEventTypes.API_KEY_REVOKED),
                    anyString(), org.mockito.ArgumentMatchers.eq(ADMIN_ID.toString()), anyString(),
                    org.mockito.ArgumentMatchers.argThat(m -> "2".equals(m.get("count"))
                            && integrationId.toString().equals(m.get("integrationIds"))
                            && since.toString().equals(m.get("since"))
                            && USER_ID.toString().equals(m.get("createdBy"))));
        }

        @Test
        @DisplayName("without a time, every key the user ever created; none found publishes no audit event")
        void withoutSinceEverything() {
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(buildUser(USER_ID, "ROLE_ADMIN")));
            given(apiKeyRepository.findActiveCreatedBy(TENANT_ID, USER_ID, Instant.EPOCH)).willReturn(List.of());

            assertThat(service.revokeKeysCreatedBy(USER_ID, null, adminPrincipal()).count()).isZero();
            // Review: nothing revoked, nothing published, so the call cannot flood the audit queue.
            then(auditEventPublisher).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("404 for a user not in the caller's tenant; nothing revoked, nothing audited")
        void otherTenantUser() {
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.revokeKeysCreatedBy(USER_ID, null, adminPrincipal()))
                    .isInstanceOf(ResourceNotFoundException.class);
            then(apiKeyRepository).shouldHaveNoInteractions();
            then(auditEventPublisher).shouldHaveNoInteractions();
        }

        private ApiKey withId(ApiKey key) {
            try {
                final var field = ApiKey.class.getDeclaredField("id");
                field.setAccessible(true);
                field.set(key, UUID.randomUUID());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            return key;
        }
    }

    // ── revokeAllPersonalKeysForUser ──────────────────────────────────────

    @Nested
    @DisplayName("revokeAllPersonalKeysForUser (backlog #0-89)")
    class RevokeAllPersonalKeys {

        @Test
        @DisplayName("returns how many keys the bulk update revoked, for the caller's audit event")
        void returnsCount() {
            given(apiKeyRepository.revokeAllPersonalKeysForUser(any(), any())).willReturn(3);

            assertThat(service.revokeAllPersonalKeysForUser(USER_ID, TENANT_ID)).isEqualTo(3);
            then(apiKeyRepository).should().revokeAllPersonalKeysForUser(any(UUID.class), any(Instant.class));
        }
    }

    // ── revokeApiKey ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("revokeApiKey")
    class RevokeApiKey {

        @Test
        @DisplayName("ADMIN can revoke any key in tenant")
        void adminRevokesAnyKey() {
            final ApiKey key = buildTenantKey();
            given(apiKeyRepository.findByIdAndTenantId(KEY_ID, TENANT_ID))
                    .willReturn(Optional.of(key));
            given(apiKeyRepository.save(any())).willAnswer(i -> i.getArgument(0));

            service.revokeApiKey(KEY_ID, adminPrincipal());

            assertThat(key.isRevoked()).isTrue();
            assertThat(key.getRevokedAt()).isNotNull();
        }

        @Test
        @DisplayName("RESPONDER can revoke own personal key")
        void responderRevokesOwnKey() {
            final User owner = buildUser(USER_ID, "ROLE_RESPONDER");
            final ApiKey key = buildPersonalKey(owner);
            given(apiKeyRepository.findByIdAndTenantId(KEY_ID, TENANT_ID))
                    .willReturn(Optional.of(key));
            given(apiKeyRepository.save(any())).willAnswer(i -> i.getArgument(0));

            service.revokeApiKey(KEY_ID, responderPrincipal());

            assertThat(key.isRevoked()).isTrue();
        }

        @Test
        @DisplayName("throws 403 when RESPONDER tries to revoke TENANT key")
        void throws403WhenResponderRevokesTenantKey() {
            final ApiKey key = buildTenantKey();
            given(apiKeyRepository.findByIdAndTenantId(KEY_ID, TENANT_ID))
                    .willReturn(Optional.of(key));

            assertThatThrownBy(() ->
                    service.revokeApiKey(KEY_ID, responderPrincipal()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("throws 409 when key already revoked")
        void throws409WhenAlreadyRevoked() {
            final ApiKey key = buildTenantKey();
            key.revoke();
            given(apiKeyRepository.findByIdAndTenantId(KEY_ID, TENANT_ID))
                    .willReturn(Optional.of(key));

            assertThatThrownBy(() ->
                    service.revokeApiKey(KEY_ID, adminPrincipal()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.CONFLICT);
        }
    }

    // ── listApiKeys ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("listApiKeys")
    class ListApiKeys {

        @Test
        @DisplayName("ADMIN sees all active keys in tenant")
        void adminSeesAllKeys() {
            given(apiKeyRepository.findActiveByTenantId(TENANT_ID))
                    .willReturn(List.of(buildTenantKey()));

            final List<ApiKeyDto> result = service.listApiKeys(adminPrincipal(), null);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("RESPONDER sees only own personal keys")
        void responderSeesOwnKeys() {
            final User owner = buildUser(USER_ID, "ROLE_RESPONDER");
            given(apiKeyRepository.findActiveByOwnerId(USER_ID))
                    .willReturn(List.of(buildPersonalKey(owner)));

            final List<ApiKeyDto> result = service.listApiKeys(responderPrincipal(), null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).keyType()).isEqualTo(ApiKeyType.PERSONAL);
        }

        @Test
        @DisplayName("a non-admin's createdBy is ignored: still only their own keys (backlog #0-89, review)")
        void responderCreatedByIgnored() {
            given(apiKeyRepository.findActiveByOwnerId(USER_ID)).willReturn(List.of());

            service.listApiKeys(responderPrincipal(), ADMIN_ID);

            then(apiKeyRepository).should().findActiveByOwnerId(USER_ID);
            then(apiKeyRepository).should(org.mockito.Mockito.never())
                    .findActiveCreatedBy(any(), any(), any());
        }
    }

    // ── ApiKeyHasher unit tests ───────────────────────────────────────────

    @Nested
    @DisplayName("ApiKeyHasher")
    class ApiKeyHasherTests {

        private final ApiKeyHasher hasher = new ApiKeyHasher();

        @Test
        @DisplayName("generateRawKey starts with ipl_")
        void generateRawKeyHasPrefix() {
            assertThat(hasher.generateRawKey()).startsWith("ipl_");
        }

        @Test
        @DisplayName("two generated keys are always different")
        void keysAreDifferent() {
            assertThat(hasher.generateRawKey())
                    .isNotEqualTo(hasher.generateRawKey());
        }

        @Test
        @DisplayName("hash is deterministic")
        void hashIsDeterministic() {
            final String key = hasher.generateRawKey();
            assertThat(hasher.hash(key)).isEqualTo(hasher.hash(key));
        }

        @Test
        @DisplayName("different keys produce different hashes")
        void differentKeysProduceDifferentHashes() {
            assertThat(hasher.hash(hasher.generateRawKey()))
                    .isNotEqualTo(hasher.hash(hasher.generateRawKey()));
        }

        @Test
        @DisplayName("verify returns true for correct key")
        void verifyCorrectKey() {
            final String key = hasher.generateRawKey();
            assertThat(hasher.verify(key, hasher.hash(key))).isTrue();
        }

        @Test
        @DisplayName("verify returns false for wrong key")
        void verifyWrongKey() {
            final String key = hasher.generateRawKey();
            assertThat(hasher.verify("ipl_wrong", hasher.hash(key))).isFalse();
        }

        @Test
        @DisplayName("extractPrefix returns first 8 chars after ipl_")
        void extractPrefixReturns8Chars() {
            final String key = hasher.generateRawKey();
            assertThat(hasher.extractPrefix(key)).hasSize(8);
        }

        @Test
        @DisplayName("isApiKey returns true for ipl_ prefix")
        void isApiKeyRecognizesPrefix() {
            assertThat(hasher.isApiKey("ipl_abc123")).isTrue();
            assertThat(hasher.isApiKey("Bearer eyJ...")).isFalse();
            assertThat(hasher.isApiKey(null)).isFalse();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static final UUID SESSION_ID = UUID.randomUUID();

    private UserPrincipal adminPrincipal() {
        return new UserPrincipal(ADMIN_ID, TENANT_ID, "admin@test.com",
                List.of("ROLE_ADMIN"), List.of(), List.of(), SESSION_ID);
    }

    private UserPrincipal responderPrincipal() {
        return new UserPrincipal(USER_ID, TENANT_ID, "user@test.com",
                List.of("ROLE_RESPONDER"), List.of());
    }

    private User buildUser(UUID id, String role) {
        return User.forTesting(id, TENANT_ID, "user@test.com",
                "hash", true, List.of(role));
    }

    private ApiKey buildTenantKey() {
        return ApiKey.createTenant(
                TENANT_ID, "Test Key", "hash", "abcdefgh",
                List.of("incidents:read"), null);
    }

    private ApiKey buildPersonalKey(User owner) {
        return ApiKey.createPersonal(
                TENANT_ID, "Personal Key", "hash", "abcdefgh",
                List.of("incidents:read"), null, owner);
    }
}