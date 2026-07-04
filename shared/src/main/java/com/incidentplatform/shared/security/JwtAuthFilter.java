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
     * Default constructor — no revocation checking.
     * Used by all services except auth-service.
     */
    public JwtAuthFilter(JwtUtils jwtUtils) {
        this.jwtUtils = jwtUtils;
        this.revocationChecker = jti -> false;
    }

    /**
     * Constructor with explicit revocation checker.
     * Used by auth-service which wires in {@code TokenRevocationService::isRevoked}.
     */
    public JwtAuthFilter(JwtUtils jwtUtils, TokenRevocationChecker revocationChecker) {
        this.jwtUtils = jwtUtils;
        this.revocationChecker = revocationChecker;
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

        final Optional<UUID> userIdOpt = jwtUtils.extractUserId(claims);
        final Optional<String> tenantIdOpt = jwtUtils.extractTenantId(claims);
        final Optional<String> emailOpt = jwtUtils.extractEmail(claims);
        final List<String> roles = jwtUtils.extractRoles(claims);

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

        final UserPrincipal principal =
                new UserPrincipal(userId, tenantId, email, roles);
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