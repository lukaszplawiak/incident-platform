package com.incidentplatform.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sends <em>real</em> tokens through the <em>real</em> {@link JwtAuthFilter}.
 *
 * <p>Added for backlog #0-11. {@code JwtAuthFilterTest} mocks
 * {@code JwtUtils}, and every controller security test builds its principal
 * by hand, so nothing ever ran a token from
 * {@link JwtUtils#generateServiceToken} through the filter — which is how
 * service tokens came to be rejected on every service without a single
 * test failing. This class is that missing test at the seam.
 */
@DisplayName("Service token authentication (real JwtUtils + real JwtAuthFilter)")
class ServiceTokenAuthenticationTest {

    private static final String SECRET = "x".repeat(64);
    private static final String OTHER_SECRET = "y".repeat(64);
    private static final String SERVICE_NAME = "notification-service";
    private static final String TENANT_ID = "acme-corp";
    /** The service under test — what a token's aud must name. */
    private static final String THIS_SERVICE = "oncall-service";

    private JwtUtils jwtUtils;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        jwtUtils = new JwtUtils(new JwtProperties(
                SECRET, Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofDays(30)));
        filter = new JwtAuthFilter(jwtUtils, jti -> false, THIS_SERVICE);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    /** Runs the filter and captures what a downstream controller would see. */
    private Result authenticate(String token, String tenantHeader) throws Exception {
        final MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/oncall/current");
        request.addHeader("Authorization", "Bearer " + token);
        if (tenantHeader != null) {
            request.addHeader("X-Tenant-Id", tenantHeader);
        }
        final AtomicReference<String> tenantSeenDownstream = new AtomicReference<>();
        final AtomicReference<Authentication> authSeenDownstream = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> {
                    tenantSeenDownstream.set(TenantContext.getOrNull());
                    authSeenDownstream.set(
                            SecurityContextHolder.getContext().getAuthentication());
                });

        return new Result(authSeenDownstream.get(), tenantSeenDownstream.get(), request);
    }

    private record Result(Authentication authentication,
                          String tenantDownstream,
                          MockHttpServletRequest request) { }

    private String tokenWith(String secret, String subject, String serviceName,
                             String tenantId, List<String> roles, Duration ttl) {
        return tokenWith(secret, subject, serviceName, tenantId, THIS_SERVICE, roles, ttl);
    }

    private String tokenWith(String secret, String subject, String serviceName,
                             String tenantId, String audience, List<String> roles,
                             Duration ttl) {
        final var builder = Jwts.builder()
                .subject(subject)
                .claim(JwtUtils.CLAIM_ROLES, roles)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ttl.toMillis()));
        if (audience != null) {
            builder.audience().add(audience);
        }
        if (serviceName != null) {
            builder.claim(JwtUtils.CLAIM_SERVICE_NAME, serviceName);
        }
        if (tenantId != null) {
            builder.claim(JwtUtils.CLAIM_TENANT_ID, tenantId);
        }
        return builder.signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Nested
    @DisplayName("generateServiceToken")
    class Generation {

        @Test
        @DisplayName("carries the requested tenant, the service name and ROLE_SERVICE")
        void carriesTenantAndRole() {
            final String token = jwtUtils.generateServiceToken(SERVICE_NAME, TENANT_ID, THIS_SERVICE);

            final Claims claims = jwtUtils.validateAndGetClaims(token).orElseThrow();
            assertThat(jwtUtils.extractTenantId(claims)).contains(TENANT_ID);
            assertThat(jwtUtils.extractServiceName(claims)).contains(SERVICE_NAME);
            assertThat(jwtUtils.extractRoles(claims)).containsExactly(SecurityRoles.ROLE_SERVICE);
            assertThat(jwtUtils.extractAudience(claims)).containsExactly(THIS_SERVICE);
        }

        @Test
        @DisplayName("rejects a tenant with whitespace, control characters or excessive length")
        void rejectsMalformedTenant() {
            assertThatThrownBy(() -> jwtUtils.generateServiceToken(SERVICE_NAME, "acme corp", THIS_SERVICE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> jwtUtils.generateServiceToken(SERVICE_NAME, "acme\r\nX: 1", THIS_SERVICE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> jwtUtils.generateServiceToken(SERVICE_NAME, "t".repeat(101), THIS_SERVICE))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects a blank audience")
        void rejectsBlankAudience() {
            assertThatThrownBy(() -> jwtUtils.generateServiceToken(SERVICE_NAME, TENANT_ID, " "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects a blank service name or tenant")
        void rejectsBlankArguments() {
            assertThatThrownBy(() -> jwtUtils.generateServiceToken(SERVICE_NAME, " ", THIS_SERVICE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> jwtUtils.generateServiceToken(null, TENANT_ID, THIS_SERVICE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("JwtAuthFilter with a service token")
    class ServiceToken {

        @Test
        @DisplayName("authenticates as ServicePrincipal with the tenant from the signed claim")
        void authenticatesServiceToken() throws Exception {
            final String token = jwtUtils.generateServiceToken(SERVICE_NAME, TENANT_ID, THIS_SERVICE);

            final Result result = authenticate(token, null);

            assertThat(result.authentication()).isNotNull();
            assertThat(result.authentication().getPrincipal())
                    .isEqualTo(new ServicePrincipal(SERVICE_NAME, TENANT_ID));
            assertThat(result.authentication().getAuthorities())
                    .extracting(Object::toString)
                    .containsExactly(SecurityRoles.ROLE_SERVICE);
            assertThat(result.tenantDownstream()).isEqualTo(TENANT_ID);
            assertThat(result.request().getAttribute(
                    TenantContext.REQUEST_ATTRIBUTE_TENANT_ID)).isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("ignores X-Tenant-Id — the tenant comes only from the signed claim")
        void ignoresTenantHeader() throws Exception {
            final String token = jwtUtils.generateServiceToken(SERVICE_NAME, TENANT_ID, THIS_SERVICE);

            final Result result = authenticate(token, "some-other-tenant");

            assertThat(result.tenantDownstream()).isEqualTo(TENANT_ID);
            assertThat(((ServicePrincipal) result.authentication().getPrincipal()).tenantId())
                    .isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("clears TenantContext after the request")
        void clearsTenantContext() throws Exception {
            authenticate(jwtUtils.generateServiceToken(SERVICE_NAME, TENANT_ID, THIS_SERVICE), null);

            assertThat(TenantContext.isSet()).isFalse();
        }

        @Test
        @DisplayName("rejects a service token with no tenantId claim")
        void rejectsMissingTenant() throws Exception {
            final String token = tokenWith(SECRET, SERVICE_NAME, SERVICE_NAME, null,
                    List.of(SecurityRoles.ROLE_SERVICE), Duration.ofMinutes(5));

            assertThat(authenticate(token, null).authentication()).isNull();
        }

        @Test
        @DisplayName("rejects a token that names a service but lacks ROLE_SERVICE")
        void rejectsMissingServiceRole() throws Exception {
            final String token = tokenWith(SECRET, SERVICE_NAME, SERVICE_NAME, TENANT_ID,
                    List.of(SecurityRoles.ROLE_ADMIN), Duration.ofMinutes(5));

            assertThat(authenticate(token, null).authentication()).isNull();
        }

        @Test
        @DisplayName("rejects a token issued for a different service (aud mismatch)")
        void rejectsWrongAudience() throws Exception {
            final String token = jwtUtils.generateServiceToken(
                    SERVICE_NAME, TENANT_ID, ServiceNames.INCIDENT_SERVICE);

            assertThat(authenticate(token, null).authentication()).isNull();
        }

        @Test
        @DisplayName("rejects a service token with no aud claim")
        void rejectsMissingAudience() throws Exception {
            final String token = tokenWith(SECRET, SERVICE_NAME, SERVICE_NAME, TENANT_ID,
                    null, List.of(SecurityRoles.ROLE_SERVICE), Duration.ofMinutes(5));

            assertThat(authenticate(token, null).authentication()).isNull();
        }

        @Test
        @DisplayName("a filter with no service name (e.g. auth-service) accepts no service token")
        void filterWithoutServiceNameRejectsServiceTokens() throws Exception {
            filter = new JwtAuthFilter(jwtUtils);
            final String token = jwtUtils.generateServiceToken(SERVICE_NAME, TENANT_ID, THIS_SERVICE);

            assertThat(authenticate(token, null).authentication()).isNull();
        }

        @Test
        @DisplayName("rejects a token signed with a different secret")
        void rejectsForeignSignature() throws Exception {
            final String token = tokenWith(OTHER_SECRET, SERVICE_NAME, SERVICE_NAME, TENANT_ID,
                    List.of(SecurityRoles.ROLE_SERVICE), Duration.ofMinutes(5));

            assertThat(authenticate(token, null).authentication()).isNull();
        }

        @Test
        @DisplayName("rejects an expired service token")
        void rejectsExpiredToken() throws Exception {
            final String token = tokenWith(SECRET, SERVICE_NAME, SERVICE_NAME, TENANT_ID,
                    List.of(SecurityRoles.ROLE_SERVICE), Duration.ofSeconds(-30));

            assertThat(authenticate(token, null).authentication()).isNull();
        }
    }

    @Nested
    @DisplayName("JwtAuthFilter with a user token (regression)")
    class UserToken {

        @Test
        @DisplayName("still authenticates as UserPrincipal")
        void stillAuthenticatesUser() throws Exception {
            final UUID userId = UUID.randomUUID();
            final String token = jwtUtils.generateToken(userId, TENANT_ID, "user@acme.com",
                    List.of(SecurityRoles.ROLE_RESPONDER), List.of(), List.of());

            final Result result = authenticate(token, null);

            assertThat(result.authentication().getPrincipal()).isInstanceOf(UserPrincipal.class);
            assertThat(((UserPrincipal) result.authentication().getPrincipal()).userId())
                    .isEqualTo(userId);
            assertThat(result.tenantDownstream()).isEqualTo(TENANT_ID);
        }
    }
}
