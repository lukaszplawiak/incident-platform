package com.incidentplatform.shared.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JWT authentication filter — validates the Bearer token on every request
 * and populates the Spring Security context.
 *
 * <h2>Why no @Component</h2>
 * Filters annotated with {@code @Component} are registered twice in Spring Boot:
 * once by the component scan (as a servlet filter) and once when added to the
 * {@code SecurityFilterChain} via {@code .addFilterBefore()}. This causes the
 * filter to execute twice per request. Removing {@code @Component} and creating
 * the bean via {@code @Bean} in {@link SharedSecurityAutoConfiguration} ensures
 * exactly one registration.
 *
 * <h2>Revocation checking</h2>
 * An optional {@link TokenRevocationChecker} is injected at construction time.
 * auth-service wires in its Redis-backed {@code TokenRevocationService}.
 * All other services use the no-op implementation ({@code jti -> false})
 * provided by {@link SharedSecurityAutoConfiguration}.
 *
 * <p>Using {@link TokenRevocationChecker} instead of a direct dependency on
 * TokenRevocationService keeps the shared module free of auth-service
 * dependencies. Each service provides its own implementation via a @Bean
 * definition — either the Redis-backed one in auth-service or the no-op
 * lambda in SharedSecurityAutoConfiguration.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String MDC_REQUEST_ID = "requestId";
    private static final String MDC_USER_ID = "userId";

    private final JwtUtils jwtUtils;

    /**
     * Strategy for checking whether a JWT has been revoked.
     * auth-service supplies the Redis-backed implementation.
     * All other services use the no-op: {@code jti -> false}.
     */
    private final TokenRevocationChecker revocationChecker;

    /**
     * This service's own name, matched against the {@code aud} claim of a
     * service token. {@code null} or blank means this service accepts
     * <em>no</em> service tokens (fail closed) — the case for auth-service
     * and for any filter built without a name.
     */
    private final String expectedAudience;

    /**
     * Default constructor — no revocation checking and no service tokens.
     */
    public JwtAuthFilter(JwtUtils jwtUtils) {
        this(jwtUtils, jti -> false, null);
    }

    /**
     * Constructor with explicit revocation checker and no service tokens.
     * Used by auth-service which wires in {@code TokenRevocationService::isRevoked}
     * and is never the target of a service-to-service call.
     */
    public JwtAuthFilter(JwtUtils jwtUtils, TokenRevocationChecker revocationChecker) {
        this(jwtUtils, revocationChecker, null);
    }

    /**
     * Full constructor. {@code expectedAudience} is this service's own name
     * ({@code spring.application.name}): a service token is authenticated
     * only if its {@code aud} claim contains it (backlog #0-11).
     */
    public JwtAuthFilter(JwtUtils jwtUtils, TokenRevocationChecker revocationChecker,
                         String expectedAudience) {
        this.jwtUtils = jwtUtils;
        this.revocationChecker = revocationChecker;
        this.expectedAudience = expectedAudience;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        final String requestId = UUID.randomUUID().toString();
        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader("X-Request-Id", requestId);

        try {
            processAuthentication(request);
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_USER_ID);
        }
    }

    private void processAuthentication(HttpServletRequest request) {
        final Optional<String> tokenOpt = extractBearerToken(request);
        if (tokenOpt.isEmpty()) {
            log.debug("No JWT token in request to: {}", request.getRequestURI());
            return;
        }

        final Optional<Claims> claimsOpt =
                jwtUtils.validateAndGetClaims(tokenOpt.get());
        if (claimsOpt.isEmpty()) {
            log.warn("Invalid JWT token for request to: {}", request.getRequestURI());
            return;
        }

        final Claims claims = claimsOpt.get();

        // Check revocation list — only auth-service has a real implementation.
        // Other services use the no-op checker (jti -> false).
        final Optional<String> jtiOpt = jwtUtils.extractJti(claims);
        if (jtiOpt.isPresent() && revocationChecker.isRevoked(jtiOpt.get())) {
            log.warn("Revoked JWT presented: jti={}, request={}",
                    jtiOpt.get(), request.getRequestURI());
            return;
        }

        // Fixed (backlog #0-11): a service token has no UUID subject and no
        // email, so it must not go through the user branch below — that
        // branch rejected every service token, and each service-to-service
        // HTTP call ended as a 401 hidden by fail-open client fallbacks.
        final Optional<String> serviceNameOpt = jwtUtils.extractServiceName(claims);
        if (serviceNameOpt.isPresent()) {
            authenticateService(request, claims, serviceNameOpt.get());
            return;
        }

        final Optional<UUID> userIdOpt = jwtUtils.extractUserId(claims);
        final Optional<String> tenantIdOpt = jwtUtils.extractTenantId(claims);
        final Optional<String> emailOpt = jwtUtils.extractEmail(claims);
        final List<String> roles   = jwtUtils.extractRoles(claims);
        final List<java.util.UUID> teamIds = jwtUtils.extractTeamIds(claims);
        final List<java.util.UUID> managedTeamIds = jwtUtils.extractManagedTeamIds(claims);
        // Empty for tokens with no associated session (service tokens,
        // dev/test tokens, API key principals never reach this filter at
        // all) — see UserPrincipal.sessionId's own Javadoc.
        final Optional<UUID> sessionIdOpt = jwtUtils.extractSessionId(claims);

        if (userIdOpt.isEmpty() || tenantIdOpt.isEmpty() || emailOpt.isEmpty()) {
            log.warn("JWT token missing required claims (userId/tenantId/email), " +
                    "request to: {}", request.getRequestURI());
            return;
        }

        final UUID userId = userIdOpt.get();
        final String tenantId = tenantIdOpt.get();
        final String email = emailOpt.get();

        TenantContext.set(tenantId);
        MDC.put(MDC_USER_ID, userId.toString());

        request.setAttribute(
                TenantContext.REQUEST_ATTRIBUTE_TENANT_ID, tenantId);

        final UserPrincipal principal = new UserPrincipal(
                userId, tenantId, email, roles, teamIds, managedTeamIds,
                sessionIdOpt.orElse(null));
        final UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities());
        authentication.setDetails(
                new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        log.debug("Authentication set for userId: {}, tenantId: {}, " +
                        "roles: {}, request: {}", userId, tenantId, roles,
                request.getRequestURI());
    }

    /**
     * Authenticates a service token ({@code serviceName} claim present).
     *
     * <p>Requires an {@code aud} claim naming <em>this</em> service, a
     * non-blank {@code tenantId} claim and {@link SecurityRoles#ROLE_SERVICE}
     * in the {@code roles} claim; the tenant comes only from the signed
     * claim, never from a request header. A token that fails any check is
     * left unauthenticated, like every other invalid token, so the entry
     * point answers 401.
     *
     * <p>The audience check is what keeps a token minted to call one service
     * from authenticating on all the others: without it, every endpoint in
     * every service that is only {@code authenticated()} would accept it,
     * and a controller would be safe from a service caller only if it
     * happened to fail on the non-user principal.
     */
    private void authenticateService(HttpServletRequest request,
                                     Claims claims,
                                     String serviceName) {
        if (expectedAudience == null || expectedAudience.isBlank()
                || !jwtUtils.extractAudience(claims).contains(expectedAudience)) {
            log.warn("Service token not issued for this service rejected: " +
                            "service={}, request={}",
                    serviceName, request.getRequestURI());
            return;
        }

        final String tenantId = jwtUtils.extractTenantId(claims)
                .filter(t -> !t.isBlank())
                .orElse(null);
        if (tenantId == null) {
            log.warn("Service token without tenantId claim rejected: " +
                    "service={}, request={}", serviceName, request.getRequestURI());
            return;
        }

        if (!jwtUtils.extractRoles(claims).contains(SecurityRoles.ROLE_SERVICE)) {
            log.warn("Service token without {} rejected: service={}, request={}",
                    SecurityRoles.ROLE_SERVICE, serviceName, request.getRequestURI());
            return;
        }

        TenantContext.set(tenantId);
        request.setAttribute(TenantContext.REQUEST_ATTRIBUTE_TENANT_ID, tenantId);

        final ServicePrincipal principal =
                new ServicePrincipal(serviceName, tenantId);
        final UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities());
        authentication.setDetails(
                new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        log.debug("Service authentication set: service={}, tenantId={}, " +
                "request={}", serviceName, tenantId, request.getRequestURI());
    }

    private Optional<String> extractBearerToken(HttpServletRequest request) {
        final String authHeader = request.getHeader(AUTHORIZATION_HEADER);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }

        final String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isBlank()) {
            log.warn("Empty Bearer token in Authorization header");
            return Optional.empty();
        }

        return Optional.of(token);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        final String path = request.getRequestURI();
        for (final String publicPath : SharedSecurityAutoConfiguration.PUBLIC_PATHS) {
            final String prefix = publicPath.endsWith("/**")
                    ? publicPath.substring(0, publicPath.length() - 3)
                    : publicPath;
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}