package com.incidentplatform.ingestion.api;

import com.incidentplatform.ingestion.config.IngestionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Backlog #28. Previously, {@code AlertIngestionController.resolveClientIp}
 * (the logic now living in {@link ClientIpResolver}) had zero test
 * coverage of any kind — nor did it check that the request came through a
 * trusted proxy before honoring {@code X-Forwarded-For}/{@code X-Real-IP},
 * both fully client-controlled headers. See this class's own Javadoc for
 * the full account of the fix.
 */
@DisplayName("ClientIpResolver")
class ClientIpResolverTest {

    private static final String UNTRUSTED_REMOTE_ADDR = "203.0.113.50";
    private static final String TRUSTED_PROXY_IP = "10.0.0.5";

    private ClientIpResolver resolverWithTrustedProxies(String... trustedProxies) {
        final IngestionProperties properties = new IngestionProperties(
                new IngestionProperties.Prometheus(500),
                1_048_576,
                List.of(trustedProxies));
        return new ClientIpResolver(properties);
    }

    @Nested
    @DisplayName("no trusted proxies configured (the default) — fail closed")
    class NoTrustedProxiesConfigured {

        @Test
        @DisplayName("ignores X-Forwarded-For entirely, uses remoteAddr")
        void ignoresXForwardedFor() {
            final ClientIpResolver resolver = resolverWithTrustedProxies();
            final MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr(UNTRUSTED_REMOTE_ADDR);
            request.addHeader("X-Forwarded-For", "198.51.100.1");

            assertThat(resolver.resolve(request)).isEqualTo(UNTRUSTED_REMOTE_ADDR);
        }

        @Test
        @DisplayName("ignores X-Real-IP entirely, uses remoteAddr")
        void ignoresXRealIp() {
            final ClientIpResolver resolver = resolverWithTrustedProxies();
            final MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr(UNTRUSTED_REMOTE_ADDR);
            request.addHeader("X-Real-IP", "198.51.100.1");

            assertThat(resolver.resolve(request)).isEqualTo(UNTRUSTED_REMOTE_ADDR);
        }
    }

    @Nested
    @DisplayName("request NOT from a trusted proxy — headers ignored regardless")
    class UntrustedRemoteAddr {

        /**
         * The actual regression test for backlog #28: this is exactly the
         * scenario that was previously exploitable — a caller reaching the
         * endpoint directly (not through the configured trusted proxy) and
         * setting an arbitrary X-Forwarded-For value to get a fresh rate
         * limit bucket on every request.
         */
        @Test
        @DisplayName("a caller connecting directly cannot spoof its IP via X-Forwarded-For")
        void directCallerCannotSpoofViaHeader() {
            final ClientIpResolver resolver = resolverWithTrustedProxies(TRUSTED_PROXY_IP);
            final MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr(UNTRUSTED_REMOTE_ADDR);
            request.addHeader("X-Forwarded-For", "1.2.3.4");

            assertThat(resolver.resolve(request))
                    .as("must use the real TCP peer, not the attacker-controlled header")
                    .isEqualTo(UNTRUSTED_REMOTE_ADDR);
        }
    }

    @Nested
    @DisplayName("request IS from a trusted proxy — headers honored")
    class TrustedRemoteAddr {

        @Test
        @DisplayName("uses the first IP in X-Forwarded-For")
        void usesFirstIpInXForwardedFor() {
            final ClientIpResolver resolver = resolverWithTrustedProxies(TRUSTED_PROXY_IP);
            final MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr(TRUSTED_PROXY_IP);
            request.addHeader("X-Forwarded-For", "198.51.100.1, 10.0.0.5");

            assertThat(resolver.resolve(request)).isEqualTo("198.51.100.1");
        }

        @Test
        @DisplayName("falls back to X-Real-IP when X-Forwarded-For is absent")
        void fallsBackToXRealIp() {
            final ClientIpResolver resolver = resolverWithTrustedProxies(TRUSTED_PROXY_IP);
            final MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr(TRUSTED_PROXY_IP);
            request.addHeader("X-Real-IP", "198.51.100.1");

            assertThat(resolver.resolve(request)).isEqualTo("198.51.100.1");
        }

        @Test
        @DisplayName("falls back to remoteAddr when neither header is present")
        void fallsBackToRemoteAddrWhenNoHeaders() {
            final ClientIpResolver resolver = resolverWithTrustedProxies(TRUSTED_PROXY_IP);
            final MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr(TRUSTED_PROXY_IP);

            assertThat(resolver.resolve(request)).isEqualTo(TRUSTED_PROXY_IP);
        }

        @Test
        @DisplayName("ignores a blank X-Forwarded-For and falls back to X-Real-IP")
        void ignoresBlankXForwardedFor() {
            final ClientIpResolver resolver = resolverWithTrustedProxies(TRUSTED_PROXY_IP);
            final MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr(TRUSTED_PROXY_IP);
            request.addHeader("X-Forwarded-For", "   ");
            request.addHeader("X-Real-IP", "198.51.100.1");

            assertThat(resolver.resolve(request)).isEqualTo("198.51.100.1");
        }
    }

    @Nested
    @DisplayName("CIDR range matching")
    class CidrRangeMatching {

        @Test
        @DisplayName("trusts a remoteAddr within a configured CIDR range")
        void trustsAddressWithinCidrRange() {
            final ClientIpResolver resolver = resolverWithTrustedProxies("172.20.0.0/16");
            final MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("172.20.5.37");
            request.addHeader("X-Forwarded-For", "198.51.100.1");

            assertThat(resolver.resolve(request)).isEqualTo("198.51.100.1");
        }

        @Test
        @DisplayName("does not trust a remoteAddr outside a configured CIDR range")
        void doesNotTrustAddressOutsideCidrRange() {
            final ClientIpResolver resolver = resolverWithTrustedProxies("172.20.0.0/16");
            final MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("172.21.5.37"); // outside /16
            request.addHeader("X-Forwarded-For", "198.51.100.1");

            assertThat(resolver.resolve(request)).isEqualTo("172.21.5.37");
        }
    }
}