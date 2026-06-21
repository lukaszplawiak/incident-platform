package com.incidentplatform.shared.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthFilter")
class JwtAuthFilterTest {

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @Mock
    private Claims claims;

    private JwtAuthFilter filter;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String TENANT_ID = "acme-corp";
    private static final String EMAIL = "user@acme.com";
    private static final List<String> ROLES = List.of(SecurityRoles.ROLE_RESPONDER);

    @BeforeEach
    void setUp() {
        filter = new JwtAuthFilter(jwtUtils);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    // ─── extractBearerToken (via reflection — private method with real branching) ──

    @Nested
    @DisplayName("extractBearerToken")
    class ExtractBearerToken {

        @Test
        @DisplayName("should extract token from valid Bearer header")
        void shouldExtractValidBearerToken() throws Exception {
            given(request.getHeader("Authorization")).willReturn("Bearer abc123");

            final Optional<String> result = invokeExtractBearerToken(request);

            assertThat(result).contains("abc123");
        }

        @Test
        @DisplayName("should trim whitespace from extracted token")
        void shouldTrimToken() throws Exception {
            given(request.getHeader("Authorization")).willReturn("Bearer  abc123  ");

            final Optional<String> result = invokeExtractBearerToken(request);

            assertThat(result).contains("abc123");
        }

        @Test
        @DisplayName("should return empty when Authorization header is missing")
        void shouldReturnEmptyWhenHeaderMissing() throws Exception {
            given(request.getHeader("Authorization")).willReturn(null);

            assertThat(invokeExtractBearerToken(request)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "abc123",           // missing "Bearer " prefix entirely
                "bearer abc123",    // wrong case
                "Basic abc123"      // different auth scheme
        })
        @DisplayName("should return empty when header doesn't start with 'Bearer '")
        void shouldReturnEmptyForNonBearerScheme(String header) throws Exception {
            given(request.getHeader("Authorization")).willReturn(header);

            assertThat(invokeExtractBearerToken(request)).isEmpty();
        }

        @Test
        @DisplayName("should return empty when Bearer token is blank")
        void shouldReturnEmptyWhenTokenBlank() throws Exception {
            given(request.getHeader("Authorization")).willReturn("Bearer    ");

            assertThat(invokeExtractBearerToken(request)).isEmpty();
        }

        @SuppressWarnings("unchecked")
        private Optional<String> invokeExtractBearerToken(HttpServletRequest req)
                throws Exception {
            final Method method = JwtAuthFilter.class
                    .getDeclaredMethod("extractBearerToken", HttpServletRequest.class);
            method.setAccessible(true);
            return (Optional<String>) method.invoke(filter, req);
        }
    }

    // ─── shouldNotFilter ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("shouldNotFilter")
    class ShouldNotFilter {

        @Test
        @DisplayName("should skip filtering for actuator health path")
        void shouldSkipActuatorHealth() {
            given(request.getRequestURI()).willReturn("/actuator/health");

            assertThat(filter.shouldNotFilter(request)).isTrue();
        }

        @Test
        @DisplayName("should skip filtering for nested actuator health subpaths")
        void shouldSkipNestedActuatorHealthSubpath() {
            given(request.getRequestURI()).willReturn("/actuator/health/liveness");

            assertThat(filter.shouldNotFilter(request)).isTrue();
        }

        @Test
        @DisplayName("should skip filtering for swagger-ui paths")
        void shouldSkipSwaggerUi() {
            given(request.getRequestURI()).willReturn("/swagger-ui/index.html");

            assertThat(filter.shouldNotFilter(request)).isTrue();
        }

        @Test
        @DisplayName("should NOT skip filtering for protected API paths")
        void shouldNotSkipProtectedApiPath() {
            given(request.getRequestURI()).willReturn("/api/v1/incidents");

            assertThat(filter.shouldNotFilter(request)).isFalse();
        }

        @Test
        @DisplayName("should NOT skip filtering for a path that merely contains " +
                "a public path as a substring (not a prefix)")
        void shouldNotSkipPathContainingPublicPathAsSubstring() {
            // Guards the prefix-matching logic: "/api/actuator/health" contains
            // "/actuator/health" but does not start with it, so must not be
            // treated as public.
            given(request.getRequestURI()).willReturn("/api/actuator/health");

            assertThat(filter.shouldNotFilter(request)).isFalse();
        }
    }

    // ─── doFilterInternal — authentication outcomes ──────────────────────────

    @Nested
    @DisplayName("doFilterInternal — authentication outcomes")
    class AuthenticationOutcomes {

        @Test
        @DisplayName("should NOT set Authentication when no Bearer token present")
        void shouldNotAuthenticateWithoutToken() throws Exception {
            given(request.getHeader("Authorization")).willReturn(null);

            filter.doFilterInternal(request, response, filterChain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            then(filterChain).should().doFilter(request, response);
        }

        @Test
        @DisplayName("should NOT set Authentication when token fails validation")
        void shouldNotAuthenticateWithInvalidToken() throws Exception {
            given(request.getHeader("Authorization")).willReturn("Bearer bad-token");
            given(jwtUtils.validateAndGetClaims("bad-token")).willReturn(Optional.empty());

            filter.doFilterInternal(request, response, filterChain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("should NOT set Authentication when claims are missing userId")
        void shouldNotAuthenticateWhenUserIdMissing() throws Exception {
            given(request.getHeader("Authorization")).willReturn("Bearer token");
            given(jwtUtils.validateAndGetClaims("token")).willReturn(Optional.of(claims));
            given(jwtUtils.extractUserId(claims)).willReturn(Optional.empty());
            given(jwtUtils.extractTenantId(claims)).willReturn(Optional.of(TENANT_ID));
            given(jwtUtils.extractEmail(claims)).willReturn(Optional.of(EMAIL));

            filter.doFilterInternal(request, response, filterChain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("should set Authentication and TenantContext for a fully valid token")
        void shouldAuthenticateWithValidToken() throws Exception {
            given(request.getHeader("Authorization")).willReturn("Bearer good-token");
            given(jwtUtils.validateAndGetClaims("good-token")).willReturn(Optional.of(claims));
            given(jwtUtils.extractUserId(claims)).willReturn(Optional.of(USER_ID));
            given(jwtUtils.extractTenantId(claims)).willReturn(Optional.of(TENANT_ID));
            given(jwtUtils.extractEmail(claims)).willReturn(Optional.of(EMAIL));
            given(jwtUtils.extractRoles(claims)).willReturn(ROLES);

            filter.doFilterInternal(request, response, filterChain);

            final var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication).isNotNull();
            assertThat(authentication.getPrincipal()).isInstanceOf(UserPrincipal.class);

            final UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
            assertThat(principal.userId()).isEqualTo(USER_ID);
            assertThat(principal.tenantId()).isEqualTo(TENANT_ID);
            assertThat(principal.email()).isEqualTo(EMAIL);
        }

        @Test
        @DisplayName("should set tenant request attribute for downstream observability tagging")
        void shouldSetTenantRequestAttribute() throws Exception {
            given(request.getHeader("Authorization")).willReturn("Bearer good-token");
            given(jwtUtils.validateAndGetClaims("good-token")).willReturn(Optional.of(claims));
            given(jwtUtils.extractUserId(claims)).willReturn(Optional.of(USER_ID));
            given(jwtUtils.extractTenantId(claims)).willReturn(Optional.of(TENANT_ID));
            given(jwtUtils.extractEmail(claims)).willReturn(Optional.of(EMAIL));
            given(jwtUtils.extractRoles(claims)).willReturn(ROLES);

            filter.doFilterInternal(request, response, filterChain);

            then(request).should()
                    .setAttribute(TenantContext.REQUEST_ATTRIBUTE_TENANT_ID, TENANT_ID);
        }
    }

    // ─── doFilterInternal — cleanup guarantees ───────────────────────────────

    @Nested
    @DisplayName("doFilterInternal — cleanup guarantees")
    class CleanupGuarantees {

        @Test
        @DisplayName("should clear TenantContext after successful processing")
        void shouldClearTenantContextOnSuccess() throws Exception {
            given(request.getHeader("Authorization")).willReturn("Bearer good-token");
            given(jwtUtils.validateAndGetClaims("good-token")).willReturn(Optional.of(claims));
            given(jwtUtils.extractUserId(claims)).willReturn(Optional.of(USER_ID));
            given(jwtUtils.extractTenantId(claims)).willReturn(Optional.of(TENANT_ID));
            given(jwtUtils.extractEmail(claims)).willReturn(Optional.of(EMAIL));
            given(jwtUtils.extractRoles(claims)).willReturn(ROLES);

            filter.doFilterInternal(request, response, filterChain);

            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("should clear TenantContext even when downstream filterChain throws")
        void shouldClearTenantContextWhenFilterChainThrows() throws Exception {
            given(request.getHeader("Authorization")).willReturn("Bearer good-token");
            given(jwtUtils.validateAndGetClaims("good-token")).willReturn(Optional.of(claims));
            given(jwtUtils.extractUserId(claims)).willReturn(Optional.of(USER_ID));
            given(jwtUtils.extractTenantId(claims)).willReturn(Optional.of(TENANT_ID));
            given(jwtUtils.extractEmail(claims)).willReturn(Optional.of(EMAIL));
            given(jwtUtils.extractRoles(claims)).willReturn(ROLES);

            willThrow(new RuntimeException("downstream failure"))
                    .given(filterChain).doFilter(any(), any());

            // when / then — exception propagates, but cleanup must still run
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                    () -> filter.doFilterInternal(request, response, filterChain));

            assertThat(TenantContext.getOrNull())
                    .as("TenantContext must be cleared even when filterChain throws — " +
                            "otherwise a leaked tenant could bleed into the next request " +
                            "handled by the same thread")
                    .isNull();
        }

        @Test
        @DisplayName("should set X-Request-Id response header for every request")
        void shouldSetRequestIdHeader() throws Exception {
            given(request.getHeader("Authorization")).willReturn(null);

            filter.doFilterInternal(request, response, filterChain);

            then(response).should().setHeader(eq("X-Request-Id"), any(String.class));
        }
    }
}