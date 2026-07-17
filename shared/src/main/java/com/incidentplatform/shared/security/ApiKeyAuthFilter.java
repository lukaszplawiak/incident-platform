package com.incidentplatform.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Authentication filter for API key credentials.
 *
 * <h2>Header format</h2>
 * {@code Authorization: ApiKey ipl_<prefix>.<secret>}
 *
 * <p>Only processes requests with the {@code ApiKey} scheme — requests with
 * {@code Bearer} (JWT) are passed through unchanged to {@link JwtAuthFilter}.
 *
 * <h2>Filter order</h2>
 * Registered BEFORE {@link JwtAuthFilter}:
 * <pre>
 *   ApiKeyAuthFilter → JwtAuthFilter → UsernamePasswordAuthenticationFilter
 * </pre>
 * If an API key is recognised and valid, authentication is set and
 * {@link JwtAuthFilter} skips processing (SecurityContext already populated).
 *
 * <h2>Strategy pattern</h2>
 * This filter delegates the actual key lookup and validation to
 * {@link ApiKeyLookupService} — an interface that each service implements.
 * auth-service provides the full DB-backed implementation.
 * Other services use a no-op that always returns empty (API keys only
 * valid through auth-service for now; future: shared key cache or
 * dedicated API gateway validation).
 *
 * <h2>Why not put this in auth-service only</h2>
 * All microservices need to authenticate API key requests — an ingestion
 * webhook, incident query, on-call lookup could all use API keys. Putting
 * the filter in {@code shared} allows each service to opt in by providing
 * its own {@link ApiKeyLookupService} bean.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    private static final String API_KEY_SCHEME  = "ApiKey ";
    private static final String AUTH_HEADER     = "Authorization";
    private static final String API_KEY_PREFIX  = "ipl_";

    /**
     * Strategy for resolving an API key to a {@link UserPrincipal}.
     * Implemented per-service; no-op by default (returns empty Optional).
     */
    public interface ApiKeyLookupService {
        Optional<UserPrincipal> lookup(String rawKey);
    }

    private final ApiKeyLookupService lookupService;

    public ApiKeyAuthFilter(ApiKeyLookupService lookupService) {
        this.lookupService = lookupService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        final String authHeader = request.getHeader(AUTH_HEADER);

        if (authHeader == null || !authHeader.startsWith(API_KEY_SCHEME)) {
            filterChain.doFilter(request, response);
            return;
        }

        final String rawKey = authHeader.substring(API_KEY_SCHEME.length()).trim();

        if (!rawKey.startsWith(API_KEY_PREFIX)) {
            log.debug("ApiKey header present but token does not start with '{}' — skipping",
                    API_KEY_PREFIX);
            filterChain.doFilter(request, response);
            return;
        }

        final java.util.Optional<UserPrincipal> principalOpt =
                lookupService.lookup(rawKey);

        if (principalOpt.isEmpty()) {
            log.warn("Invalid or revoked API key for request to: {}",
                    request.getRequestURI());
            // Don't short-circuit — let downstream filters/handlers return 401
            filterChain.doFilter(request, response);
            return;
        }

        final UserPrincipal principal = principalOpt.get();

        TenantContext.set(principal.tenantId());

        final UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        log.debug("API key authenticated: tenantId={}, keyType={}, request={}",
                principal.tenantId(),
                principal.isApiKey() ? "api-key" : "unknown",
                request.getRequestURI());

        filterChain.doFilter(request, response);
    }
}