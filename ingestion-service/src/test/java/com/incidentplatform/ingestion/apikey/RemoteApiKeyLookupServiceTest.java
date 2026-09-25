package com.incidentplatform.ingestion.apikey;

import com.incidentplatform.ingestion.api.ClientIpResolver;
import com.incidentplatform.ingestion.ratelimit.AuthFailureRateLimiter;
import com.incidentplatform.shared.security.ApiKeyAuthFilter.ApiKeyLookupResult;
import com.incidentplatform.shared.security.ApiKeyHashing;
import com.incidentplatform.shared.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

/**
 * Tests for {@link RemoteApiKeyLookupService}: the order of checks (cache,
 * failed-attempt limiter, introspection) and the mapping of each answer to
 * the response the sender sees — 401 is a definite "no", 503 "try later".
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RemoteApiKeyLookupService")
class RemoteApiKeyLookupServiceTest {

    private static final String RAW_KEY = "ipl_raw-key-value";
    private static final String HASH = ApiKeyHashing.sha256Hex(RAW_KEY);
    private static final String CLIENT_IP = "203.0.113.7";

    @Mock
    private CachingApiKeyIntrospectionClient introspectionClient;

    @Mock
    private AuthFailureRateLimiter authFailureRateLimiter;

    @Mock
    private ClientIpResolver clientIpResolver;

    private RemoteApiKeyLookupService service;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        service = new RemoteApiKeyLookupService(
                introspectionClient, authFailureRateLimiter, clientIpResolver);
        request = new MockHttpServletRequest("POST", "/api/v1/alerts/prometheus");
        lenient().when(clientIpResolver.resolve(request)).thenReturn(CLIENT_IP);
        lenient().when(introspectionClient.findCached(anyString())).thenReturn(Optional.empty());
    }

    private static IntrospectedApiKey key(UUID teamId) {
        return new IntrospectedApiKey(UUID.randomUUID(), "platform-operator", teamId,
                List.of("alerts:ingest"), null);
    }

    @Test
    @DisplayName("an active key becomes a least-privilege API-key principal of the key's tenant and team")
    void activeKeyIsAuthenticated() {
        final UUID teamId = UUID.randomUUID();
        final IntrospectedApiKey key = key(teamId);
        given(introspectionClient.introspect(HASH)).willReturn(Optional.of(key));

        final ApiKeyLookupResult result = service.lookup(RAW_KEY, request);

        assertThat(result).isInstanceOf(ApiKeyLookupResult.Authenticated.class);
        final UserPrincipal principal = ((ApiKeyLookupResult.Authenticated) result).principal();
        assertThat(principal.tenantId()).isEqualTo("platform-operator");
        assertThat(principal.userId()).isEqualTo(key.keyId());
        assertThat(principal.isApiKey()).isTrue();
        assertThat(principal.roles()).isEmpty();
        assertThat(principal.teamIds()).containsExactly(teamId);
        assertThat(principal.hasScope("alerts:ingest")).isTrue();
    }

    @Test
    @DisplayName("a key without a team gets no teamIds")
    void keyWithoutTeam() {
        given(introspectionClient.introspect(HASH)).willReturn(Optional.of(key(null)));

        final UserPrincipal principal =
                ((ApiKeyLookupResult.Authenticated) service.lookup(RAW_KEY, request)).principal();

        assertThat(principal.teamIds()).isEmpty();
    }

    @Test
    @DisplayName("a cached key is let through before the limiter — a blocked IP cannot lock it out")
    void cachedKeyBypassesLimiter() {
        given(introspectionClient.findCached(HASH)).willReturn(Optional.of(key(null)));

        assertThat(service.lookup(RAW_KEY, request))
                .isInstanceOf(ApiKeyLookupResult.Authenticated.class);
        then(authFailureRateLimiter).should(never()).isBlocked(anyString());
        then(introspectionClient).should(never()).introspect(anyString());
    }

    @Test
    @DisplayName("a blocked IP is throttled without a call to auth-service")
    void blockedIpIsThrottled() {
        given(authFailureRateLimiter.isBlocked(CLIENT_IP)).willReturn(true);
        given(authFailureRateLimiter.retryAfter()).willReturn(Duration.ofSeconds(60));

        final ApiKeyLookupResult result = service.lookup(RAW_KEY, request);

        assertThat(result).isEqualTo(new ApiKeyLookupResult.Throttled(Duration.ofSeconds(60)));
        then(introspectionClient).should(never()).introspect(anyString());
    }

    @Test
    @DisplayName("an unknown key is Invalid (401) and counted as a failure for the IP")
    void unknownKeyIsInvalid() {
        given(introspectionClient.introspect(HASH)).willReturn(Optional.empty());

        assertThat(service.lookup(RAW_KEY, request)).isInstanceOf(ApiKeyLookupResult.Invalid.class);
        then(authFailureRateLimiter).should().recordFailure(CLIENT_IP);
    }

    @Test
    @DisplayName("auth-service unavailable is Unavailable (503 + Retry-After), not a failure of the client")
    void unavailableIsNotInvalid() {
        given(introspectionClient.introspect(HASH))
                .willThrow(new ApiKeyIntrospectionUnavailableException("down", null));

        assertThat(service.lookup(RAW_KEY, request)).isEqualTo(
                new ApiKeyLookupResult.Unavailable(RemoteApiKeyLookupService.UNAVAILABLE_RETRY_AFTER));
        then(authFailureRateLimiter).should(never()).recordFailure(anyString());
    }

    @Test
    @DisplayName("only the hash reaches the introspection client, never the raw key")
    void onlyHashIsSent() {
        given(introspectionClient.introspect(HASH)).willReturn(Optional.empty());

        service.lookup(RAW_KEY, request);

        then(introspectionClient).should().introspect(HASH);
        then(introspectionClient).should(never()).introspect(RAW_KEY);
    }
}
