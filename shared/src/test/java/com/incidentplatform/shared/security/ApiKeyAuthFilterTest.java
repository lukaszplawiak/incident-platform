package com.incidentplatform.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.incidentplatform.shared.security.ApiKeyAuthFilter.ApiKeyLookupResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ApiKeyAuthFilter (backlog #0-16)")
class ApiKeyAuthFilterTest {

    private static final String KEY = "ipl_" + "k".repeat(32);
    private static final String TENANT = "acme-corp";

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AtomicReference<String> keySeen = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private ApiKeyAuthFilter filter(ApiKeyLookupResult result) {
        return new ApiKeyAuthFilter((rawKey, request) -> {
            keySeen.set(rawKey);
            return result;
        }, objectMapper);
    }

    private static UserPrincipal principal() {
        return new UserPrincipal(UUID.randomUUID(), TENANT, "api-key:am", List.of(),
                List.of(), List.of(), true, List.of(ApiScopes.ALERTS_INGEST), null);
    }

    private record Outcome(MockHttpServletResponse response, MockHttpServletRequest request,
                           boolean chainCalled, Authentication auth, String tenant) { }

    private Outcome run(ApiKeyAuthFilter filter, String path, String authorization) throws Exception {
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final AtomicBoolean called = new AtomicBoolean();
        final AtomicReference<Authentication> auth = new AtomicReference<>();
        final AtomicReference<String> tenant = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> {
            called.set(true);
            auth.set(SecurityContextHolder.getContext().getAuthentication());
            tenant.set(TenantContext.getOrNull());
        });
        return new Outcome(response, request, called.get(), auth.get(), tenant.get());
    }

    @Test
    @DisplayName("ApiKey and Bearer schemes both authenticate; tenant set on context and request, then cleared")
    void authenticatesBothSchemes() throws Exception {
        for (final String header : List.of("ApiKey " + KEY, "Bearer " + KEY, "bearer " + KEY)) {
            final Outcome outcome = run(filter(new ApiKeyLookupResult.Authenticated(principal())),
                    "/api/v1/alerts/prometheus", header);

            assertThat(keySeen.get()).isEqualTo(KEY);
            assertThat(outcome.chainCalled()).isTrue();
            assertThat(outcome.auth().getPrincipal()).isInstanceOf(UserPrincipal.class);
            assertThat(outcome.tenant()).isEqualTo(TENANT);
            assertThat(outcome.request().getAttribute(TenantContext.REQUEST_ATTRIBUTE_TENANT_ID))
                    .isEqualTo(TENANT);
            assertThat(TenantContext.isSet()).isFalse();
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("invalid key: 401 with WWW-Authenticate naming the scheme used, chain not called")
    void invalid() throws Exception {
        final Outcome outcome = run(filter(new ApiKeyLookupResult.Invalid()),
                "/api/v1/alerts/prometheus", "Bearer " + KEY);

        assertThat(outcome.chainCalled()).isFalse();
        assertThat(outcome.response().getStatus()).isEqualTo(401);
        assertThat(outcome.response().getHeader("WWW-Authenticate"))
                .isEqualTo("Bearer realm=\"incident-platform\", error=\"invalid_token\"");
        assertThat(outcome.response().getContentAsString()).contains("\"UNAUTHORIZED\"");
        assertThat(outcome.response().getHeader("X-Request-Id")).isNotBlank();
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    @DisplayName("validator unavailable: 503 with Retry-After, so a sender retries")
    void unavailable() throws Exception {
        final Outcome outcome = run(filter(new ApiKeyLookupResult.Unavailable(Duration.ofSeconds(30))),
                "/api/v1/alerts/prometheus", "ApiKey " + KEY);

        assertThat(outcome.chainCalled()).isFalse();
        assertThat(outcome.response().getStatus()).isEqualTo(503);
        assertThat(outcome.response().getHeader("Retry-After")).isEqualTo("30");
        assertThat(outcome.response().getContentAsString()).contains("AUTHENTICATION_UNAVAILABLE");
    }

    @Test
    @DisplayName("throttled: 429 with Retry-After of at least one second")
    void throttled() throws Exception {
        final Outcome outcome = run(filter(new ApiKeyLookupResult.Throttled(Duration.ZERO)),
                "/api/v1/alerts/prometheus", "ApiKey " + KEY);

        assertThat(outcome.response().getStatus()).isEqualTo(429);
        assertThat(outcome.response().getHeader("Retry-After")).isEqualTo("1");
    }

    @Test
    @DisplayName("no key, a JWT, a non-ipl_ ApiKey value or a public path: passed on untouched")
    void passesThrough() throws Exception {
        final ApiKeyAuthFilter filter = filter(new ApiKeyLookupResult.Invalid());
        for (final String header : new String[] {null, "Bearer eyJhbGciOi.x.y", "ApiKey other_123"}) {
            final Outcome outcome = run(filter, "/api/v1/alerts/prometheus", header);
            assertThat(outcome.chainCalled()).isTrue();
            assertThat(outcome.response().getStatus()).isEqualTo(200);
        }
        assertThat(run(filter, "/actuator/health", "ApiKey " + KEY).chainCalled()).isTrue();
        assertThat(keySeen.get()).isNull();
    }
}
