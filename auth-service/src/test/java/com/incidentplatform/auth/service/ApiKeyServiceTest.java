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
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UserPrincipal;
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

    private ApiKeyService service;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID   ADMIN_ID  = UUID.randomUUID();
    private static final UUID   USER_ID   = UUID.randomUUID();
    private static final UUID   KEY_ID    = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ApiKeyService(
                apiKeyRepository, userRepository,
                apiKeyHasher, auditEventPublisher);
        TenantContext.set(TENANT_ID);

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
        }

        @Test
        @DisplayName("creates PERSONAL key — any authenticated user")
        void createsPersonalKey() {
            final User owner = buildUser(USER_ID, "ROLE_RESPONDER");
            given(apiKeyRepository.findActiveByTenantId(TENANT_ID))
                    .willReturn(List.of());
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
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
            given(apiKeyRepository.findActiveByTenantId(TENANT_ID))
                    .willReturn(List.of());

            assertThatThrownBy(() -> service.createApiKey(
                    new CreateApiKeyRequest(
                            "Bad scope", ApiKeyType.PERSONAL,
                            List.of(ApiKeyScope.TEAMS_WRITE), null), // ADMIN only
                    responderPrincipal()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.FORBIDDEN);
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

            final List<ApiKeyDto> result = service.listApiKeys(adminPrincipal());

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("RESPONDER sees only own personal keys")
        void responderSeesOwnKeys() {
            final User owner = buildUser(USER_ID, "ROLE_RESPONDER");
            given(apiKeyRepository.findActiveByOwnerId(USER_ID))
                    .willReturn(List.of(buildPersonalKey(owner)));

            final List<ApiKeyDto> result = service.listApiKeys(responderPrincipal());

            assertThat(result).hasSize(1);
            assertThat(result.get(0).keyType()).isEqualTo(ApiKeyType.PERSONAL);
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

    private UserPrincipal adminPrincipal() {
        return new UserPrincipal(ADMIN_ID, TENANT_ID, "admin@test.com",
                List.of("ROLE_ADMIN"), List.of());
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