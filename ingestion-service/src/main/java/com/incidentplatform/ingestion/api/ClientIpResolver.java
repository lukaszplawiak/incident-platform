package com.incidentplatform.ingestion.api;

import com.incidentplatform.ingestion.config.IngestionProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves the real client IP address for a request, for use by
 * {@link com.incidentplatform.ingestion.ratelimit.RateLimitingService}
 * (via {@link AlertIngestionController}).
 *
 * <h2>Fixed: previously trusted {@code X-Forwarded-For}/{@code X-Real-IP}
 * unconditionally</h2>
 * Extracted from {@code AlertIngestionController.resolveClientIp}, which
 * read these headers directly with no check that the request actually
 * came through a proxy trusted to set them accurately (and to strip any
 * client-supplied copy before forwarding). Both headers are fully
 * client-controlled — without this check, a caller reaching the endpoint
 * directly could set an arbitrary value per request, getting a fresh
 * RateLimitingService IP-based bucket every time and trivially evading
 * IP-based rate limiting entirely.
 *
 * <p>Now only honored when {@code request.getRemoteAddr()} — the actual
 * TCP peer, which cannot be spoofed by the HTTP client — matches a
 * configured, trusted proxy IP or CIDR range
 * ({@link IngestionProperties#trustedProxies()}). When the immediate
 * connection isn't from a trusted proxy (including the default, empty
 * configuration — see that field's Javadoc for why empty is the secure
 * default), both headers are ignored entirely and
 * {@code request.getRemoteAddr()} is used directly.
 */
@Component
public class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);

    private final List<IpAddressMatcher> trustedProxyMatchers;

    public ClientIpResolver(IngestionProperties properties) {
        this.trustedProxyMatchers = properties.trustedProxies().stream()
                .map(IpAddressMatcher::new)
                .toList();

        if (trustedProxyMatchers.isEmpty()) {
            log.warn("No ingestion.trusted-proxies configured — " +
                    "X-Forwarded-For/X-Real-IP will never be honored; " +
                    "rate limiting will always use the direct TCP peer address. " +
                    "If this service runs behind a real reverse proxy or " +
                    "ingress controller, configure ingestion.trusted-proxies " +
                    "with its IP/CIDR range, or IP-based rate limiting will " +
                    "bucket every request under the proxy's single IP.");
        }
    }

    public String resolve(HttpServletRequest request) {
        final String remoteAddr = request.getRemoteAddr();

        if (!isFromTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }

        final String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            // Leftmost entry is the original client in the conventional
            // X-Forwarded-For chain format (client, proxy1, proxy2, ...).
            // This assumes a single well-behaved proxy hop — with multiple
            // chained proxies, only the immediate one (remoteAddr, already
            // verified trusted above) is actually authenticated; anything
            // it forwards inside the header value itself is still exactly
            // as trustworthy as that one hop chooses to make it. Consistent
            // with this method's scope: verifying the DIRECT connection is
            // trusted, not establishing a fully verified multi-hop chain.
            return xForwardedFor.split(",")[0].trim();
        }

        final String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }

        return remoteAddr;
    }

    private boolean isFromTrustedProxy(String remoteAddr) {
        return trustedProxyMatchers.stream()
                .anyMatch(matcher -> matcher.matches(remoteAddr));
    }
}