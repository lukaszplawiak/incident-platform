package com.incidentplatform.ingestion.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;

/**
 * Rejects oversized request bodies before Spring MVC ever attempts to
 * deserialize them.
 *
 * <h2>Fixed: the previous size check ran after the payload was already
 * fully parsed</h2>
 * {@code AlertIngestionController.ingestAlerts} takes
 * {@code @RequestBody JsonNode rawPayload} — Spring's
 * {@code HttpMessageConverter} machinery fully reads and parses the
 * entire request body into a Jackson {@code JsonNode} tree
 * <em>before</em> the controller method body starts running at all. The
 * controller's own {@code payloadString.length() > MAX_PAYLOAD_BYTES}
 * check therefore ran only after the expensive, memory-consuming parse
 * of however large the payload actually was — a caller sending a 500 MB
 * body would still force a full parse of 500 MB into memory before being
 * rejected, making that check essentially decorative against the
 * resource-exhaustion risk it was meant to guard against.
 *
 * <p>Separately confirmed while investigating this: neither
 * {@code server.tomcat.max-swallow-size} (already set in
 * {@code application.yml}, but this only bounds how much of an
 * already-erroring request Tomcat discards while closing the connection
 * — not a body-size cap for successful requests) nor
 * {@code server.tomcat.max-http-form-post-size} (which, despite its
 * generic-sounding name, only applies to
 * {@code application/x-www-form-urlencoded} form bodies — verified
 * against current Spring Boot documentation and a long-standing,
 * still-open upstream issue about exactly this confusion) provide any
 * container-level protection for a JSON {@code @RequestBody} like this
 * one. There was no layer — container or application — actually
 * enforcing a size limit before this filter.
 *
 * <h2>Two layers, for two different attacker behaviors</h2>
 * <ol>
 *   <li><b>{@code Content-Length} header check</b> — the fast path.
 *       Virtually every real HTTP client sending a JSON body (including
 *       Alertmanager, this endpoint's actual caller) sets
 *       {@code Content-Length} accurately. When present and over the
 *       limit, the request is rejected with {@code 413} immediately,
 *       without reading a single byte of the body.</li>
 *   <li><b>Streaming byte-count wrapper</b> — defense in depth for a
 *       request using chunked transfer encoding (no {@code Content-Length}
 *       header at all) or one that understates it. {@link
 *       SizeLimitingRequestWrapper} counts bytes as Spring's message
 *       converter reads them and throws once the limit is exceeded,
 *       aborting the parse mid-stream rather than after it completes.
 *       Honesty note: because this exception surfaces from inside
 *       {@code HttpMessageConverter}'s own read loop, Spring's default
 *       exception handling may resolve it to {@code 400 Bad Request}
 *       rather than a clean {@code 413} in this specific path (unlike
 *       the header-check path above, which this filter fully controls).
 *       The HTTP status is secondary here — what matters is that the
 *       parse is aborted well before the configured limit is exceeded by
 *       any significant margin, not left to run unbounded.</li>
 * </ol>
 *
 * <p>Registered directly as a {@code FilterRegistrationBean} (see
 * {@link PayloadSizeLimitFilterConfig}) with the highest possible
 * precedence — before Spring Security's own filter chain, not added
 * into it — since this is a resource-protection concern that applies to
 * every request regardless of authentication outcome, not a security
 * decision that belongs alongside {@code JwtAuthFilter}.
 */
public class PayloadSizeLimitFilter extends OncePerRequestFilter {

    private static final Logger log =
            LoggerFactory.getLogger(PayloadSizeLimitFilter.class);

    private final int maxPayloadBytes;

    public PayloadSizeLimitFilter(int maxPayloadBytes) {
        this.maxPayloadBytes = maxPayloadBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        final long declaredLength = request.getContentLengthLong();
        if (declaredLength > maxPayloadBytes) {
            log.warn("Rejecting request — declared Content-Length {} exceeds " +
                            "limit {}: method={}, uri={}",
                    declaredLength, maxPayloadBytes,
                    request.getMethod(), request.getRequestURI());
            respondPayloadTooLarge(response);
            return;
        }

        try {
            filterChain.doFilter(
                    new SizeLimitingRequestWrapper(request, maxPayloadBytes),
                    response);
        } catch (PayloadTooLargeException e) {
            log.warn("Rejecting request — body exceeded limit {} while streaming " +
                            "(no accurate Content-Length was declared): " +
                            "method={}, uri={}",
                    maxPayloadBytes, request.getMethod(), request.getRequestURI());
            if (!response.isCommitted()) {
                respondPayloadTooLarge(response);
            }
        }
    }

    private void respondPayloadTooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\":\"Payload too large\",\"maxBytes\":" + maxPayloadBytes + "}");
    }

    /**
     * Thrown by {@link SizeLimitingInputStream} once the byte count read
     * so far exceeds the configured limit. Caught in
     * {@link #doFilterInternal} — see this class's Javadoc for the honest
     * caveat about the resulting HTTP status in this path.
     */
    static class PayloadTooLargeException extends IOException {
        PayloadTooLargeException(String message) {
            super(message);
        }
    }

    /**
     * Wraps the request so {@code getInputStream()} returns a
     * byte-counting stream instead of the raw one — every downstream
     * reader (Spring MVC's {@code HttpMessageConverter} included) reads
     * through the limit check without needing to know it's there.
     */
    private static class SizeLimitingRequestWrapper extends HttpServletRequestWrapper {

        private final int maxPayloadBytes;

        SizeLimitingRequestWrapper(HttpServletRequest request, int maxPayloadBytes) {
            super(request);
            this.maxPayloadBytes = maxPayloadBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new SizeLimitingInputStream(
                    super.getInputStream(), maxPayloadBytes);
        }
    }

    /**
     * A {@link ServletInputStream} that throws {@link PayloadTooLargeException}
     * as soon as the number of bytes read exceeds {@code maxPayloadBytes} —
     * mid-stream, not after buffering the whole body first.
     */
    private static class SizeLimitingInputStream extends ServletInputStream {

        private final InputStream delegate;
        private final int maxPayloadBytes;
        private long bytesRead = 0;

        SizeLimitingInputStream(InputStream delegate, int maxPayloadBytes) {
            this.delegate = delegate;
            this.maxPayloadBytes = maxPayloadBytes;
        }

        @Override
        public int read() throws IOException {
            final int b = delegate.read();
            if (b != -1) {
                checkLimit(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            final int count = delegate.read(b, off, len);
            if (count > 0) {
                checkLimit(count);
            }
            return count;
        }

        private void checkLimit(int justRead) throws IOException {
            bytesRead += justRead;
            if (bytesRead > maxPayloadBytes) {
                throw new PayloadTooLargeException(
                        "Request body exceeded " + maxPayloadBytes +
                                " bytes while streaming (read " + bytesRead +
                                " so far)");
            }
        }

        // ── ServletInputStream's async-read API — delegated straight
        // through. This filter only needs to intercept read(), not
        // participate in async I/O readiness signaling.

        @Override
        public boolean isFinished() {
            try {
                return delegate.available() == 0;
            } catch (IOException e) {
                return true;
            }
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // Not supported — this filter is used for synchronous Servlet
            // processing only (Spring MVC's default mode), not async
            // request handling, which this codebase doesn't use for this
            // endpoint.
            throw new UnsupportedOperationException(
                    "Async request handling is not supported by PayloadSizeLimitFilter");
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}