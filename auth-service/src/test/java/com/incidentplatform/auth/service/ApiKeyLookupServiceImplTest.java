package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.ApiKey;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.shared.security.ApiKeyAuthFilter.ApiKeyLookupResult;
import com.incidentplatform.shared.security.UserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyLookupServiceImpl")
class ApiKeyLookupServiceImplTest {

    private static final String TENANT = "acme-corp";
    private static final String RAW = "ipl_" + "k".repeat(32);

    @Mock private ApiKeyIntrospectionService introspectionService;

    private final ApiKeyHasher hasher = new ApiKeyHasher();

    private ApiKeyLookupServiceImpl lookup() {
        return new ApiKeyLookupServiceImpl(introspectionService, hasher);
    }

    @Test
    @DisplayName("resolves by the shared SHA-256 hash; a tenant key is a RESPONDER with its scopes and team")
    void tenantKey() {
        final ApiKey key = ApiKey.createTenant(TENANT, "alertmanager", "h", "kkkkkkkk",
                List.of("alerts:ingest"), null);
        ReflectionTestUtils.setField(key, "id", UUID.randomUUID());
        final UUID teamId = UUID.randomUUID();
        given(introspectionService.resolve(hasher.hash(RAW)))
                .willReturn(Optional.of(new ApiKeyIntrospectionService.ActiveApiKey(key, teamId)));

        final UserPrincipal principal = ((ApiKeyLookupResult.Authenticated) lookup().lookup(RAW, null)).principal();

        assertThat(principal.userId()).isEqualTo(key.getId());
        assertThat(principal.tenantId()).isEqualTo(TENANT);
        assertThat(principal.roles()).containsExactly("ROLE_RESPONDER");
        assertThat(principal.teamIds()).containsExactly(teamId);
        assertThat(principal.scopes()).containsExactly("alerts:ingest");
        assertThat(principal.isApiKey()).isTrue();
    }

    @Test
    @DisplayName("a personal key carries its owner's id and roles")
    void personalKey() {
        final UUID ownerId = UUID.randomUUID();
        final User owner = User.forTesting(ownerId, TENANT, "o@acme.test", "hash", true,
                List.of("ROLE_ADMIN"));
        final ApiKey key = ApiKey.createPersonal(TENANT, "mine", "h", "kkkkkkkk",
                List.of("incidents:read"), null, owner);
        given(introspectionService.resolve(hasher.hash(RAW)))
                .willReturn(Optional.of(new ApiKeyIntrospectionService.ActiveApiKey(key, null)));

        final UserPrincipal principal = ((ApiKeyLookupResult.Authenticated) lookup().lookup(RAW, null)).principal();

        assertThat(principal.userId()).isEqualTo(ownerId);
        assertThat(principal.roles()).containsExactly("ROLE_ADMIN");
        assertThat(principal.teamIds()).isEmpty();
    }

    @Test
    @DisplayName("an inactive key yields no principal")
    void inactive() {
        given(introspectionService.resolve(hasher.hash(RAW))).willReturn(Optional.empty());

        assertThat(lookup().lookup(RAW, null)).isInstanceOf(ApiKeyLookupResult.Invalid.class);
    }
}
