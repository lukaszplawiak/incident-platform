package com.incidentplatform.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sends <em>real</em> purpose tokens through the <em>real</em>
 * {@link JwtAuthFilter} (backlog #0-16), in the style of
 * {@link ServiceTokenAuthenticationTest}: a purpose token acts for no tenant,
 * so every check that keeps it from turning into something broader must hold
 * at this seam, not only in a mocked unit test.
 */
@DisplayName("Purpose token authentication (real JwtUtils + real JwtAuthFilter)")
class PurposeTokenAuthenticationTest {

    private static final String SECRET = "x".repeat(64);
    private static final String OTHER_SECRET = "y".repeat(64);
    private static final String CALLER = "ingestion-service";
    private static final String THIS_SERVICE = ServiceNames.AUTH_SERVICE;
    private static final String PURPOSE = TokenPurposes.API_KEY_INTROSPECTION;

    private JwtUtils jwtUtils;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        jwtUtils = new JwtUtils(new JwtProperties(
                SECRET, Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofDays(30)));
        filter = new JwtAuthFilter(jwtUtils, jti -> false, THIS_SERVICE, Set.of(PURPOSE));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private Result authenticate(JwtAuthFilter jwtFilter, String token, String tenantHeader)
            throws Exception {
        final MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/internal/api-keys/introspect");
        request.addHeader("Authorization", "Bearer " + token);
        if (tenantHeader != null) {
            request.addHeader("X-Tenant-Id", tenantHeader);
        }
        final AtomicReference<String> tenantSeen = new AtomicReference<>();
        final AtomicReference<Authentication> authSeen = new AtomicReference<>();
        jwtFilter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            tenantSeen.set(TenantContext.getOrNull());
            authSeen.set(SecurityContextHolder.getContext().getAuthentication());
        });
        return new Result(authSeen.get(), tenantSeen.get(), request);
    }

    private Result authenticate(String token) throws Exception {
        return authenticate(filter, token, null);
    }

    private record Result(Authentication authentication, String tenantDownstream,
                          MockHttpServletRequest request) { }

    /** Hand-built token, for claim combinations JwtUtils refuses to mint. */
    private static String handBuilt(String secret, String purpose, String audience,
                                    String tenantId, String serviceName, String subject) {
        final var builder = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000));
        if (subject != null) {
            builder.subject(subject);
        }
        if (purpose != null) {
            builder.claim(JwtUtils.CLAIM_PURPOSE, purpose);
        }
        if (audience != null) {
            builder.audience().add(audience);
        }
        if (tenantId != null) {
            builder.claim(JwtUtils.CLAIM_TENANT_ID, tenantId);
        }
        if (serviceName != null) {
            builder.claim(JwtUtils.CLAIM_SERVICE_NAME, serviceName);
        }
        return builder.signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Nested
    @DisplayName("generatePurposeToken")
    class Generation {

        @Test
        @DisplayName("carries purpose, audience, subject and jti — and no tenant, serviceName or roles")
        void claimShape() {
            final String token = jwtUtils.generatePurposeToken(CALLER, PURPOSE, THIS_SERVICE);

            final Claims claims = jwtUtils.validateAndGetClaims(token).orElseThrow();
            assertThat(jwtUtils.extractPurpose(claims)).contains(PURPOSE);
            assertThat(jwtUtils.extractAudience(claims)).containsExactly(THIS_SERVICE);
            assertThat(claims.getSubject()).isEqualTo(CALLER);
            assertThat(jwtUtils.extractJti(claims)).isPresent();
            assertThat(jwtUtils.extractTenantId(claims)).isEmpty();
            assertThat(jwtUtils.extractServiceName(claims)).isEmpty();
            assertThat(jwtUtils.extractRoles(claims)).isEmpty();
        }

        @Test
        @DisplayName("rejects blank arguments")
        void rejectsBlankArguments() {
            assertThatThrownBy(() -> jwtUtils.generatePurposeToken(" ", PURPOSE, THIS_SERVICE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> jwtUtils.generatePurposeToken(CALLER, null, THIS_SERVICE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> jwtUtils.generatePurposeToken(CALLER, PURPOSE, ""))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("accepted")
    class Accepted {

        @Test
        @DisplayName("authenticates as IntrospectionPrincipal with only ROLE_API_KEY_INTROSPECTION")
        void authenticates() throws Exception {
            final Result result = authenticate(
                    jwtUtils.generatePurposeToken(CALLER, PURPOSE, THIS_SERVICE));

            assertThat(result.authentication()).isNotNull();
            assertThat(result.authentication().getPrincipal())
                    .isEqualTo(new IntrospectionPrincipal(CALLER));
            assertThat(result.authentication().getAuthorities())
                    .extracting(Object::toString)
                    .containsExactly(SecurityRoles.ROLE_API_KEY_INTROSPECTION);
        }

        @Test
        @DisplayName("sets no tenant, and ignores X-Tenant-Id")
        void noTenant() throws Exception {
            final Result result = authenticate(filter,
                    jwtUtils.generatePurposeToken(CALLER, PURPOSE, THIS_SERVICE), "acme-corp");

            assertThat(result.tenantDownstream()).isNull();
            assertThat(result.request().getAttribute(
                    TenantContext.REQUEST_ATTRIBUTE_TENANT_ID)).isNull();
        }
    }

    @Nested
    @DisplayName("rejected (left unauthenticated, so the entry point answers 401)")
    class Rejected {

        @Test
        @DisplayName("by a filter that accepts no purposes — every service but auth-service")
        void filterWithoutPurposes() throws Exception {
            final JwtAuthFilter noPurposes = new JwtAuthFilter(jwtUtils, jti -> false, THIS_SERVICE);

            assertThat(authenticate(noPurposes,
                    jwtUtils.generatePurposeToken(CALLER, PURPOSE, THIS_SERVICE), null)
                    .authentication()).isNull();
        }

        @Test
        @DisplayName("when the purpose is not one this service accepts")
        void unknownPurpose() throws Exception {
            assertThat(authenticate(jwtUtils.generatePurposeToken(CALLER, "delete-everything",
                    THIS_SERVICE)).authentication()).isNull();
        }

        @Test
        @DisplayName("when aud names another service")
        void wrongAudience() throws Exception {
            assertThat(authenticate(jwtUtils.generatePurposeToken(CALLER, PURPOSE,
                    ServiceNames.ONCALL_SERVICE)).authentication()).isNull();
        }

        @Test
        @DisplayName("when the token also carries a tenantId claim")
        void carriesTenant() throws Exception {
            assertThat(authenticate(handBuilt(SECRET, PURPOSE, THIS_SERVICE, "acme-corp",
                    null, CALLER)).authentication()).isNull();
            assertThat(TenantContext.isSet()).isFalse();
        }

        @Test
        @DisplayName("when the token also carries a serviceName claim")
        void carriesServiceName() throws Exception {
            assertThat(authenticate(handBuilt(SECRET, PURPOSE, THIS_SERVICE, null,
                    CALLER, CALLER)).authentication()).isNull();
        }

        @Test
        @DisplayName("when the subject is missing")
        void noSubject() throws Exception {
            assertThat(authenticate(handBuilt(SECRET, PURPOSE, THIS_SERVICE, null,
                    null, null)).authentication()).isNull();
        }

        @Test
        @DisplayName("when signed with another secret")
        void foreignSignature() throws Exception {
            assertThat(authenticate(handBuilt(OTHER_SECRET, PURPOSE, THIS_SERVICE, null,
                    null, CALLER)).authentication()).isNull();
        }

        @Test
        @DisplayName("when its jti is revoked")
        void revoked() throws Exception {
            final JwtAuthFilter revokingAll =
                    new JwtAuthFilter(jwtUtils, jti -> true, THIS_SERVICE, Set.of(PURPOSE));

            assertThat(authenticate(revokingAll,
                    jwtUtils.generatePurposeToken(CALLER, PURPOSE, THIS_SERVICE), null)
                    .authentication()).isNull();
        }
    }

    @Nested
    @DisplayName("Bearer ipl_... (an API key sent with the Bearer scheme)")
    class BearerApiKey {

        @Test
        @DisplayName("is left alone: not parsed as a JWT, no authentication")
        void notParsed() throws Exception {
            final Result result = authenticate("ipl_" + "a".repeat(32));

            assertThat(result.authentication()).isNull();
        }
    }
}
