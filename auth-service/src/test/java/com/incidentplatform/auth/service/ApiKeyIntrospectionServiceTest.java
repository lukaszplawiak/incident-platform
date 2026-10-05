package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.ApiKey;
import com.incidentplatform.auth.domain.Integration;
import com.incidentplatform.auth.domain.Team;
import com.incidentplatform.auth.dto.ApiKeyIntrospectionResponse;
import com.incidentplatform.auth.repository.ApiKeyRepository;
import com.incidentplatform.auth.repository.IntegrationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyIntrospectionService (backlog #0-16)")
class ApiKeyIntrospectionServiceTest {

    private static final String TENANT = "acme-corp";
    private static final String HASH = "b".repeat(64);

    @Mock private ApiKeyRepository apiKeyRepository;
    @Mock private IntegrationRepository integrationRepository;
    @Mock private ApiKeyUsageRecorder usageRecorder;
    @Mock private TenantAccessService tenantAccessService;
    @InjectMocks private ApiKeyIntrospectionService service;

    private static ApiKey key(Instant expiresAt, UUID integrationId) {
        final ApiKey key = ApiKey.createTenant(TENANT, "alertmanager", HASH, "abcdefgh",
                List.of("alerts:ingest"), expiresAt);
        ReflectionTestUtils.setField(key, "id", UUID.randomUUID());
        key.setIntegrationId(integrationId);
        return key;
    }

    @Test
    @DisplayName("active key: tenant, team of its Integration, scopes, expiry — and usage is recorded")
    void activeIntegrationKey() {
        final UUID integrationId = UUID.randomUUID();
        final UUID teamId = UUID.randomUUID();
        final Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);
        final ApiKey apiKey = key(expiresAt, integrationId);
        given(apiKeyRepository.findActiveByHash(HASH)).willReturn(Optional.of(apiKey));
        given(integrationRepository.findById(integrationId)).willReturn(Optional.of(
                Integration.create(TENANT, "am", "prometheus",
                        Team.forTesting(teamId, TENANT, "sre"), apiKey, null)));

        final ApiKeyIntrospectionResponse response = service.introspect(HASH);

        assertThat(response.active()).isTrue();
        assertThat(response.keyId()).isEqualTo(apiKey.getId());
        assertThat(response.tenantId()).isEqualTo(TENANT);
        assertThat(response.teamId()).isEqualTo(teamId);
        assertThat(response.scopes()).containsExactly("alerts:ingest");
        assertThat(response.expiresAt()).isEqualTo(expiresAt);
        then(usageRecorder).should().recordUsage(apiKey.getId());
    }

    @Test
    @DisplayName("PERSONAL key with alerts:ingest: inactive for introspection, and not recorded as used")
    void personalKeyIsNotActiveForIntrospection() {
        final ApiKey personal = ApiKey.createPersonal(TENANT, "my-script", HASH, "abcdefgh",
                List.of("alerts:ingest"), null, owner());
        ReflectionTestUtils.setField(personal, "id", UUID.randomUUID());
        given(apiKeyRepository.findActiveByHash(HASH)).willReturn(Optional.of(personal));

        final ApiKeyIntrospectionResponse response = service.introspect(HASH);

        // Same answer as an unknown key: no reason given (RFC 7662 §2.2).
        assertThat(response).isEqualTo(ApiKeyIntrospectionResponse.inactive());
        then(usageRecorder).should(never()).recordUsage(any());
    }

    @Test
    @DisplayName("PERSONAL key still resolves for auth-service's own endpoints")
    void personalKeyStillResolves() {
        final ApiKey personal = ApiKey.createPersonal(TENANT, "my-script", HASH, "abcdefgh",
                List.of("incidents:read"), null, owner());
        ReflectionTestUtils.setField(personal, "id", UUID.randomUUID());
        given(apiKeyRepository.findActiveByHash(HASH)).willReturn(Optional.of(personal));

        assertThat(service.resolve(HASH)).isPresent();
        then(usageRecorder).should().recordUsage(personal.getId());
    }

    @Test
    @DisplayName("unknown or revoked key: inactive, nothing else, no usage recorded")
    void unknownKey() {
        given(apiKeyRepository.findActiveByHash(HASH)).willReturn(Optional.empty());

        final ApiKeyIntrospectionResponse response = service.introspect(HASH);

        assertThat(response).isEqualTo(ApiKeyIntrospectionResponse.inactive());
        then(usageRecorder).should(never()).recordUsage(any());
    }

    @Test
    @DisplayName("expired key: inactive")
    void expiredKey() {
        given(apiKeyRepository.findActiveByHash(HASH))
                .willReturn(Optional.of(key(Instant.now().minusSeconds(1), null)));

        assertThat(service.introspect(HASH).active()).isFalse();
        then(usageRecorder).should(never()).recordUsage(any());
    }

    @Test
    @DisplayName("key without an Integration, or with a revoked one: active, no team")
    void noTeam() {
        given(apiKeyRepository.findActiveByHash(HASH)).willReturn(Optional.of(key(null, null)));
        assertThat(service.introspect(HASH).teamId()).isNull();

        final UUID integrationId = UUID.randomUUID();
        final ApiKey apiKey = key(null, integrationId);
        final Integration revoked = Integration.create(TENANT, "am", "prometheus",
                Team.forTesting(UUID.randomUUID(), TENANT, "sre"), null, null);
        revoked.revoke();
        given(apiKeyRepository.findActiveByHash(HASH)).willReturn(Optional.of(apiKey));
        given(integrationRepository.findById(integrationId)).willReturn(Optional.of(revoked));

        final ApiKeyIntrospectionResponse response = service.introspect(HASH);
        assertThat(response.active()).isTrue();
        assertThat(response.teamId()).isNull();
    }

    private static com.incidentplatform.auth.domain.User owner() {
        return com.incidentplatform.auth.domain.User.forTesting(UUID.randomUUID(), TENANT, "owner@acme.example",
                "hash", true, List.of("ROLE_RESPONDER"));
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("tenant suspension and deactivated owners (backlog #0-82)")
    class Suspension {

        private void access(com.incidentplatform.shared.security.TenantAccess access) {
            given(tenantAccessService.accessOf(TENANT)).willReturn(access);
        }

        @Test
        @DisplayName("suspended in full: no key resolves, for auth-service or for ingestion, and none is marked used")
        void fullSuspension() {
            final ApiKey apiKey = key(null, null);
            given(apiKeyRepository.findActiveByHash(HASH)).willReturn(Optional.of(apiKey));
            access(com.incidentplatform.shared.security.TenantAccess.NONE);

            assertThat(service.resolve(HASH)).isEmpty();
            assertThat(service.introspect(HASH)).isEqualTo(ApiKeyIntrospectionResponse.inactive());
            then(usageRecorder).should(never()).recordUsage(any());
        }

        @Test
        @DisplayName("read-only: a key still reads in auth-service, but ingestion (a write) gets it paused, not inactive")
        void readOnly() {
            final ApiKey apiKey = key(null, null);
            given(apiKeyRepository.findActiveByHash(HASH)).willReturn(Optional.of(apiKey));
            access(com.incidentplatform.shared.security.TenantAccess.READ_ONLY);

            assertThat(service.resolve(HASH)).isPresent();
            final ApiKeyIntrospectionResponse response = service.introspect(HASH);
            assertThat(response).isEqualTo(ApiKeyIntrospectionResponse.pausedForWrites());
            assertThat(response.active()).isFalse();
            assertThat(response.tenantId()).isNull();
            // Once, by resolve(): a paused introspection is no use of the key.
            then(usageRecorder).should(org.mockito.Mockito.times(1)).recordUsage(apiKey.getId());
        }

        @Test
        @DisplayName("read-only: a PERSONAL key is plain inactive for ingestion, never paused (paused would say it is valid)")
        void readOnlyPersonalKeyNotPaused() {
            final ApiKey personal = ApiKey.createPersonal(TENANT, "my-script", HASH, "abcdefgh",
                    List.of("alerts:ingest"), null, owner());
            ReflectionTestUtils.setField(personal, "id", UUID.randomUUID());
            given(apiKeyRepository.findActiveByHash(HASH)).willReturn(Optional.of(personal));
            access(com.incidentplatform.shared.security.TenantAccess.READ_ONLY);

            assertThat(service.introspect(HASH)).isEqualTo(ApiKeyIntrospectionResponse.inactive());
        }

        @Test
        @DisplayName("on the wire: paused is {active:false, paused:true}; inactive stays {active:false} (the contract ingestion parses)")
        void pausedWireFormat() throws Exception {
            final com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

            assertThat(mapper.writeValueAsString(ApiKeyIntrospectionResponse.pausedForWrites()))
                    .isEqualTo("{\"active\":false,\"paused\":true}");
            assertThat(mapper.writeValueAsString(ApiKeyIntrospectionResponse.inactive()))
                    .isEqualTo("{\"active\":false}");
        }

        @Test
        @DisplayName("a personal key of a deactivated owner does not resolve; an active owner's does")
        void deactivatedOwner() {
            final com.incidentplatform.auth.domain.User inactive = owner();
            inactive.setActive(false);
            final ApiKey personal = ApiKey.createPersonal(TENANT, "my-script", HASH, "abcdefgh",
                    List.of("incidents:read"), null, inactive);
            ReflectionTestUtils.setField(personal, "id", UUID.randomUUID());
            given(apiKeyRepository.findActiveByHash(HASH)).willReturn(Optional.of(personal));
            access(com.incidentplatform.shared.security.TenantAccess.FULL);

            assertThat(service.resolve(HASH)).isEmpty();
            then(usageRecorder).should(never()).recordUsage(any());
        }
    }
}
