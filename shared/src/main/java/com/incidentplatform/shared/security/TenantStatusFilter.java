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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Refuses the requests of a suspended tenant's users and API keys (backlog
 * #0-82), in every service's filter chain: added by
 * {@link SharedSecurityAutoConfiguration#buildCommonSecurity} after
 * {@code JwtAuthFilter}, so a service that declares its own chain gets it too.
 *
 * <ul>
 *   <li>{@link TenantAccess#NONE} (suspended in full): every request, 403
 *       {@code TENANT_SUSPENDED}.</li>
 *   <li>{@link TenantAccess#READ_ONLY}: GET, HEAD and OPTIONS go on; any other
 *       method is 403 {@code TENANT_READ_ONLY}, unless its method and path are
 *       one of the service's {@code allowedWrites} (auth-service lists account
 *       security: logout, password change, MFA, an admin shutting out a key or
 *       a person), so a billing hold never stops anyone from securing their
 *       account.</li>
 * </ul>
 *
 * <p>Only a {@link UserPrincipal} (a person's JWT or an API key) is checked. A
 * service token acts for a tenant inside the platform; the background work it
 * belongs to is paused where it is scheduled or consumed, not refused here. A
 * request with no principal (a public endpoint) is left to that endpoint, which
 * checks the tenant itself where it matters (login, refresh).
 *
 * <h2>Each allowed write names its method (found in review)</h2>
 * An entry is {@code "METHOD /ant/pattern"}, e.g. {@code "DELETE /api/v1/api-keys/*"}.
 * A bare pattern would admit every write method on the path, so a write added
 * later under an allowed prefix (a {@code PUT} next to a {@code DELETE}) would
 * pass a read-only tenant silently. An entry without a method, or with one
 * that is not a write, is refused when the filter is built: the service fails
 * to start rather than allow more than intended.
 *
 * <h2>Which path is matched against {@code allowedWrites}</h2>
 * The path within the application as Spring MVC routes it
 * ({@link UrlPathHelper}: URL-decoded, {@code ;params} removed, {@code //}
 * collapsed), not the raw request URI — a raw {@code /api/v1/auth/logout;x}
 * must not miss the list for a request MVC sends to logout, and a decoded
 * path is what a pattern like {@code /api/v1/auth/mfa/**} is written against.
 * A path with a {@code .} or {@code ..} segment is never an allowed write:
 * {@code /api/v1/auth/mfa/../../tenants/settings} matches the MFA pattern but
 * is routed elsewhere. Spring Security's firewall already rejects such paths
 * before this filter; this is the second line, and it fails closed.
 *
 * <h2>A read-only refusal that asks to retry (backlog #0-82, step 2)</h2>
 * A service that sets {@code tenant-status.read-only.retry-after} answers a
 * read-only tenant's refused write 503 + {@code Retry-After} instead of 403.
 * ingestion-service does: there a write is an alert, and an alert source such
 * as Alertmanager drops an alert answered 4xx and keeps and retries one
 * answered 5xx. A read-only tenant's alerts are paused, not refused — the
 * answer its key's introspection already gives (503, {@code Retry-After: 300})
 * — and this keeps it so for a key ingestion-service cached as active before
 * the suspension.
 *
 * <p>Built in {@code buildCommonSecurity}, not declared as a bean: a filter
 * bean would also be registered as a plain servlet filter, outside the chain.
 */
public class TenantStatusFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantStatusFilter.class);

    private static final Set<String> SAFE_METHODS = Set.of(
            HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name());
    private static final Set<String> WRITE_METHODS = Set.of(
            HttpMethod.POST.name(), HttpMethod.PUT.name(), HttpMethod.PATCH.name(), HttpMethod.DELETE.name());

    private final TenantStatusProvider provider;
    private final ObjectMapper objectMapper;
    private final List<AllowedWrite> allowedWrites;
    /** {@code null}: a read-only refusal is 403; otherwise 503 with this Retry-After. */
    private final Duration readOnlyRetryAfter;
    private final AntPathMatcher matcher = new AntPathMatcher();
    private final UrlPathHelper urlPathHelper = new UrlPathHelper();

    /** One allowed write: an HTTP write method and an Ant path pattern. */
    private record AllowedWrite(String method, String pattern) { }

    /**
     * @param allowedWrites {@code "METHOD /ant/pattern"} entries a read-only
     *                      tenant may still send (account security); empty for
     *                      a service without any
     * @throws IllegalArgumentException for an entry without a write method or
     *         without a pattern starting with {@code /}
     */
    public TenantStatusFilter(TenantStatusProvider provider, ObjectMapper objectMapper,
                              List<String> allowedWrites) {
        this(provider, objectMapper, allowedWrites, null);
    }

    /**
     * @param readOnlyRetryAfter {@code null} to refuse a read-only tenant's
     *                           write with 403; otherwise it is refused with 503
     *                           and this {@code Retry-After} (a whole number of
     *                           seconds, at least one)
     * @throws IllegalArgumentException also for a {@code readOnlyRetryAfter}
     *         shorter than one second
     */
    public TenantStatusFilter(TenantStatusProvider provider, ObjectMapper objectMapper,
                              List<String> allowedWrites, Duration readOnlyRetryAfter) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.allowedWrites = allowedWrites.stream().map(TenantStatusFilter::parse).toList();
        if (readOnlyRetryAfter != null && readOnlyRetryAfter.toSeconds() < 1) {
            throw new IllegalArgumentException("tenant-status.read-only.retry-after must be at least one second: "
                    + readOnlyRetryAfter);
        }
        this.readOnlyRetryAfter = readOnlyRetryAfter;
    }

    private static AllowedWrite parse(String entry) {
        final String[] parts = entry == null ? new String[0] : entry.strip().split("\\s+");
        if (parts.length != 2 || SAFE_METHODS.contains(parts[0]) || !WRITE_METHODS.contains(parts[0])
                || !parts[1].startsWith("/")) {
            throw new IllegalArgumentException("tenant-status.read-only.allowed-writes entry must be "
                    + "\"<POST|PUT|PATCH|DELETE> /path/pattern\": " + entry);
        }
        return new AllowedWrite(parts[0], parts[1]);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            chain.doFilter(request, response);
            return;
        }
        final TenantAccess access = provider.accessOf(principal.tenantId());
        switch (access) {
            case FULL -> chain.doFilter(request, response);
            case NONE -> refuse(response, request, principal, HttpStatus.FORBIDDEN, ErrorCodes.TENANT_SUSPENDED,
                    "This organisation's account is suspended. Contact the platform operator.");
            case READ_ONLY -> {
                if (SAFE_METHODS.contains(request.getMethod()) || allowedWrite(request)) {
                    chain.doFilter(request, response);
                } else if (readOnlyRetryAfter != null) {
                    response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(readOnlyRetryAfter.toSeconds()));
                    refuse(response, request, principal, HttpStatus.SERVICE_UNAVAILABLE,
                            ErrorCodes.TENANT_READ_ONLY,
                            "This organisation's account is suspended to read-only: "
                                    + "changes are paused until it is resumed. Retry later.");
                } else {
                    refuse(response, request, principal, HttpStatus.FORBIDDEN, ErrorCodes.TENANT_READ_ONLY,
                            "This organisation's account is suspended to read-only: "
                                    + "changes are refused until it is resumed.");
                }
            }
        }
    }

    private boolean allowedWrite(HttpServletRequest request) {
        if (allowedWrites.isEmpty()) {
            return false;
        }
        final String path = urlPathHelper.getPathWithinApplication(request);
        if (hasDotSegment(path)) {
            return false;
        }
        return allowedWrites.stream().anyMatch(allowed -> allowed.method().equals(request.getMethod())
                && matcher.match(allowed.pattern(), path));
    }

    private static boolean hasDotSegment(String path) {
        for (final String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private void refuse(HttpServletResponse response, HttpServletRequest request, UserPrincipal principal,
                        HttpStatus status, String errorCode, String message) throws IOException {
        log.info("Request of a suspended tenant refused: tenant={}, user={}, apiKey={}, method={}, code={}",
                principal.tenantId(), principal.userId(), principal.isApiKey(), request.getMethod(), errorCode);
        final String fromHeader = response.getHeader("X-Request-Id");
        final String requestId = fromHeader != null && !fromHeader.isBlank()
                ? fromHeader : Objects.requireNonNullElse(MDC.get("requestId"), "unknown");
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ErrorResponse.of(status.value(), errorCode, message, requestId));
    }
}
