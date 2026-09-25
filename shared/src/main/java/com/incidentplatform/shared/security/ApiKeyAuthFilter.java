package com.incidentplatform.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.shared.dto.ErrorResponse;
import com.incidentplatform.shared.exception.ErrorCodes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

/**
 * Authentication filter for API key credentials.
 *
 * <h2>Header format</h2>
 * {@code Authorization: ApiKey ipl_...} or {@code Authorization: Bearer ipl_...}.
 * Added (backlog #0-16): the {@code Bearer} form, told apart from a JWT by
 * the {@code ipl_} prefix, because it is the scheme every webhook sender can
 * produce (Alertmanager's {@code authorization.type} can send either).
 * {@link JwtAuthFilter} leaves {@code Bearer ipl_...} alone. Anything else
 * passes through unchanged to {@link JwtAuthFilter}.
 *
 * <h2>Filter order</h2>
 * Registered BEFORE {@link JwtAuthFilter}:
 * <pre>
 *   ApiKeyAuthFilter → JwtAuthFilter → UsernamePasswordAuthenticationFilter
 * </pre>
 *
 * <h2>Strategy pattern</h2>
 * This filter delegates the lookup to {@link ApiKeyLookupService}, which each
 * service implements: auth-service against its own table, ingestion-service by
 * introspection over HTTP (backlog #0-16), every other service with the no-op
 * default that finds nothing.
 *
 * <h2>Fixed (backlog #0-16): the answer to a sender that retries</h2>
 * A request that presents an API key is answered here, not passed on, because
 * the right status depends on <em>why</em> the key was not accepted, and an
 * alert sender such as Alertmanager retries network errors and 5xx but drops
 * every 4xx:
 * <ul>
 *   <li>{@link ApiKeyLookupResult.Invalid} — unknown, revoked or expired:
 *       <b>401</b> with {@code WWW-Authenticate}, a definite "no";</li>
 *   <li>{@link ApiKeyLookupResult.Unavailable} — the key could not be checked
 *       (auth-service unreachable): <b>503</b> with {@code Retry-After}, so the
 *       alert is retried instead of lost;</li>
 *   <li>{@link ApiKeyLookupResult.Throttled} — too many failed attempts from
 *       this client: <b>429</b> with {@code Retry-After}.</li>
 * </ul>
 * Before, an invalid key fell through to the entry point's generic 401, and an
 * unreachable validator could not be told apart from a wrong key at all.
 *
 * <p>Also fixed: on success the tenant is set on the request attribute as well
 * as {@link TenantContext} (the ThreadLocal is cleared before the observation
 * filter reads it, see {@link TenantContext#REQUEST_ATTRIBUTE_TENANT_ID}), and
 * both are cleared when the request leaves this filter.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    private static final String AUTH_HEADER     = "Authorization";
    private static final String API_KEY_SCHEME  = "ApiKey";
    private static final String BEARER_SCHEME   = "Bearer";
    private static final String API_KEY_PREFIX  = "ipl_";
    private static final String REALM           = "incident-platform";
    private static final String MDC_REQUEST_ID  = "requestId";

    /**
     * Strategy for resolving an API key. Implemented per service; the default
     * finds nothing.
     */
    public interface ApiKeyLookupService {
        ApiKeyLookupResult lookup(String rawKey, HttpServletRequest request);
    }

    /** Outcome of a lookup — see the class Javadoc for how each is answered. */
    public sealed interface ApiKeyLookupResult {

        record Authenticated(UserPrincipal principal) implements ApiKeyLookupResult {
            public Authenticated {
                if (principal == null) {
                    throw new IllegalArgumentException("principal must not be null");
                }
            }
        }

        record Invalid() implements ApiKeyLookupResult { }

        record Unavailable(Duration retryAfter) implements ApiKeyLookupResult { }

        record Throttled(Duration retryAfter) implements ApiKeyLookupResult { }
    }

    private final ApiKeyLookupService lookupService;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(ApiKeyLookupService lookupService, ObjectMapper objectMapper) {
        this.lookupService = lookupService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        final Credential credential = extract(request.getHeader(AUTH_HEADER));
        if (credential == null) {
            filterChain.doFilter(request, response);
            return;
        }

        final ApiKeyLookupResult result = lookupService.lookup(credential.rawKey(), request);
        switch (result) {
            case ApiKeyLookupResult.Authenticated authenticated ->
                    continueAuthenticated(authenticated.principal(), request, response, filterChain);
            case ApiKeyLookupResult.Invalid invalid -> {
                log.warn("Invalid or revoked API key for request to: {}", request.getRequestURI());
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, credential.scheme()
                        + " realm=\"" + REALM + "\", error=\"invalid_token\"");
                writeError(response, HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED,
                        "The API key is not valid.");
            }
            case ApiKeyLookupResult.Unavailable unavailable -> {
                log.warn("API key could not be checked, answering 503: request={}",
                        request.getRequestURI());
                response.setHeader(HttpHeaders.RETRY_AFTER, seconds(unavailable.retryAfter()));
                writeError(response, HttpStatus.SERVICE_UNAVAILABLE,
                        ErrorCodes.AUTHENTICATION_UNAVAILABLE,
                        "The API key cannot be checked right now. Retry later.");
            }
            case ApiKeyLookupResult.Throttled throttled -> {
                response.setHeader(HttpHeaders.RETRY_AFTER, seconds(throttled.retryAfter()));
                writeError(response, HttpStatus.TOO_MANY_REQUESTS,
                        ErrorCodes.TOO_MANY_FAILED_AUTHENTICATIONS,
                        "Too many failed authentications from this client. Retry later.");
            }
        }
    }

    private void continueAuthenticated(UserPrincipal principal,
                                       HttpServletRequest request,
                                       HttpServletResponse response,
                                       FilterChain filterChain)
            throws ServletException, IOException {
        TenantContext.set(principal.tenantId());
        request.setAttribute(TenantContext.REQUEST_ATTRIBUTE_TENANT_ID, principal.tenantId());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        log.debug("API key authenticated: tenantId={}, request={}",
                principal.tenantId(), request.getRequestURI());
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Writes the error body. The request never reaches {@link JwtAuthFilter},
     * which normally assigns the request id, so one is assigned here — and
     * removed again, since nothing downstream will.
     */
    private void writeError(HttpServletResponse response, HttpStatus status,
                            String errorCode, String message) throws IOException {
        final String requestId = UUID.randomUUID().toString();
        response.setHeader("X-Request-Id", requestId);
        MDC.put(MDC_REQUEST_ID, requestId);
        try {
            response.setStatus(status.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(),
                    ErrorResponse.of(status.value(), errorCode, message, requestId));
        } finally {
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    private static String seconds(Duration duration) {
        return Long.toString(Math.max(1, duration.toSeconds()));
    }

    private record Credential(String scheme, String rawKey) { }

    /** An {@code ipl_} key under the ApiKey or Bearer scheme, else {@code null}. */
    private static Credential extract(String header) {
        if (header == null) {
            return null;
        }
        for (final String scheme : new String[] {API_KEY_SCHEME, BEARER_SCHEME}) {
            if (header.regionMatches(true, 0, scheme + " ", 0, scheme.length() + 1)) {
                final String value = header.substring(scheme.length() + 1).trim();
                return value.startsWith(API_KEY_PREFIX) ? new Credential(scheme, value) : null;
            }
        }
        return null;
    }

    /** Public paths are never authenticated, same as {@link JwtAuthFilter}. */
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
