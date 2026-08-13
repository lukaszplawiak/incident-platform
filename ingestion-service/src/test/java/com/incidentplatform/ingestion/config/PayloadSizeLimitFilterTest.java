package com.incidentplatform.ingestion.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Backlog #25. Previously had no test coverage of any kind — the
 * ineffective, too-late check this filter replaces
 * ({@code AlertIngestionController}'s old
 * {@code payloadString.length() > MAX_PAYLOAD_BYTES}) was also never
 * tested. See {@link PayloadSizeLimitFilter}'s own Javadoc for the full
 * account of the bug being fixed.
 */
@DisplayName("PayloadSizeLimitFilter")
class PayloadSizeLimitFilterTest {

    private static final int MAX_PAYLOAD_BYTES = 100;

    private final PayloadSizeLimitFilter filter =
            new PayloadSizeLimitFilter(MAX_PAYLOAD_BYTES);

    @Nested
    @DisplayName("Content-Length header check — the fast path")
    class ContentLengthCheck {

        @Test
        @DisplayName("rejects with 413 when Content-Length exceeds the limit, " +
                "without invoking the filter chain at all")
        void rejectsWhenContentLengthExceedsLimit() throws Exception {
            final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/alerts/generic");
            request.setContent(new byte[MAX_PAYLOAD_BYTES + 1]);
            final MockHttpServletResponse response = new MockHttpServletResponse();
            final RecordingFilterChain chain = new RecordingFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(413);
            assertThat(chain.invoked).isFalse();
        }

        @Test
        @DisplayName("passes through when Content-Length is within the limit")
        void passesThroughWhenContentLengthWithinLimit() throws Exception {
            final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/alerts/generic");
            request.setContent(new byte[MAX_PAYLOAD_BYTES]);
            final MockHttpServletResponse response = new MockHttpServletResponse();
            final RecordingFilterChain chain = new RecordingFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(chain.invoked).isTrue();
            assertThat(response.getStatus()).isEqualTo(200); // MockHttpServletResponse default
        }
    }

    @Nested
    @DisplayName("streaming byte-count wrapper — defense in depth for chunked/understated bodies")
    class StreamingWrapperCheck {

        /**
         * Wraps a real {@code MockHttpServletRequest} (which has the
         * actual body bytes) so {@code getContentLength()}/
         * {@code getContentLengthLong()} report -1 ("unknown") — simulating
         * chunked transfer encoding — while {@code getInputStream()}
         * still delegates to the real, streamable body underneath.
         *
         * <p>Fixed: an earlier version of this test tried
         * {@code request.setContentLength(-1)} directly on
         * {@code MockHttpServletRequest}, assuming that setter existed —
         * it doesn't (in the Spring version this project uses, content
         * length is derived internally from whatever was passed to
         * {@code setContent(byte[])}, with no independent setter to
         * override it). Wrapping in a plain, standard
         * {@code HttpServletRequestWrapper} sidesteps that entirely —
         * this technique doesn't depend on any particular mock library's
         * internal field layout, only on the standard Servlet API.
         */
        private static HttpServletRequest requestWithUnknownContentLength(byte[] body) {
            final MockHttpServletRequest mockRequest =
                    new MockHttpServletRequest("POST", "/api/v1/alerts/generic");
            mockRequest.setContent(body);
            return new HttpServletRequestWrapper(mockRequest) {
                @Override
                public int getContentLength() {
                    return -1;
                }

                @Override
                public long getContentLengthLong() {
                    return -1L;
                }
            };
        }

        /**
         * Simulates a request with no declared Content-Length (chunked
         * transfer encoding) whose actual body exceeds the limit — the
         * scenario the header check alone cannot catch.
         */
        @Test
        @DisplayName("aborts reading mid-stream once the limit is exceeded, " +
                "when no accurate Content-Length was declared")
        void abortsStreamingOnceLimitExceededWithoutContentLength() throws Exception {
            final byte[] oversizedBody = new byte[MAX_PAYLOAD_BYTES * 2];
            final HttpServletRequest request = requestWithUnknownContentLength(oversizedBody);
            final MockHttpServletResponse response = new MockHttpServletResponse();
            final FullyReadingFilterChain chain = new FullyReadingFilterChain();

            filter.doFilter(request, response, chain);

            // The chain's attempt to fully read the stream should have been
            // interrupted partway through — it must not have successfully
            // read the entire oversized body.
            assertThat(chain.bytesSuccessfullyRead).isLessThan(oversizedBody.length);
            assertThat(chain.threwPayloadTooLarge).isTrue();
        }

        @Test
        @DisplayName("reads the full body normally when it's within the limit, " +
                "even with no declared Content-Length")
        void readsFullBodyWhenWithinLimitWithoutContentLength() throws Exception {
            final byte[] body = "small alert payload".getBytes(StandardCharsets.UTF_8);
            final HttpServletRequest request = requestWithUnknownContentLength(body);
            final MockHttpServletResponse response = new MockHttpServletResponse();
            final FullyReadingFilterChain chain = new FullyReadingFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(chain.bytesSuccessfullyRead).isEqualTo(body.length);
            assertThat(chain.threwPayloadTooLarge).isFalse();
        }
    }

    /** Records whether the chain was ever invoked — used for the fast-path tests. */
    private static class RecordingFilterChain implements jakarta.servlet.FilterChain {
        boolean invoked = false;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                             jakarta.servlet.ServletResponse response) {
            invoked = true;
        }
    }

    /**
     * Simulates what Spring MVC's HttpMessageConverter actually does:
     * reads the request's InputStream through to the end (or until it
     * throws), byte by byte via the buffered read(byte[], int, int)
     * overload — matching PayloadSizeLimitFilter's Javadoc note that the
     * wrapper must intercept reads made by real downstream consumers, not
     * just direct calls made by this test.
     */
    private static class FullyReadingFilterChain implements jakarta.servlet.FilterChain {
        int bytesSuccessfullyRead = 0;
        boolean threwPayloadTooLarge = false;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                             jakarta.servlet.ServletResponse response)
                throws IOException, ServletException {
            final byte[] buffer = new byte[16];
            try (var in = request.getInputStream()) {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    bytesSuccessfullyRead += n;
                }
            } catch (PayloadSizeLimitFilter.PayloadTooLargeException e) {
                // Package-private — directly catchable here since this test
                // is in the same package (com.incidentplatform.ingestion.config)
                // as PayloadSizeLimitFilter.
                threwPayloadTooLarge = true;
            }
        }
    }
}