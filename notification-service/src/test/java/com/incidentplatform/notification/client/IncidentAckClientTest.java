package com.incidentplatform.notification.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.incidentplatform.shared.security.ServiceTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link IncidentAckClient} using WireMock.
 *
 * <p>RestClient.RequestBodySpec has two overloaded body() methods —
 * body(Object) and body(BodyInserter). Mockito cannot stub the correct
 * overload at registration time, causing NPE at runtime regardless of
 * which stubbing API is used. WireMock starts a real HTTP server so the
 * production RestClient makes real HTTP calls — no fluent API mocking needed.
 *
 * <p>HttpClient is configured with HTTP/1.1 only — WireMock standalone
 * does not support HTTP/2, and JdkClientHttpRequestFactory defaults to
 * HTTP/2 which causes RST_STREAM errors against WireMock.
 *
 * <h2>Why the error-path tests changed from "returns false" to "throws"</h2>
 * {@code client} here is constructed via plain {@code new
 * IncidentAckClient(...)} — no Spring context, no Resilience4j AOP proxy.
 * The class's own fix (see its Javadoc) removed the internal catch that
 * used to swallow failures inline; real failures now propagate so the
 * *real*, Spring-managed proxy can see and record them in production.
 * Calling the bare object directly here means those same failures now
 * surface as thrown exceptions in this test instead of a silently
 * swallowed {@code false}. What actually produces the graceful
 * {@code false} in production — {@code acknowledgeIncidentFallback},
 * called by the proxy once it catches the propagated failure — is
 * tested directly and separately below.
 *
 * <p>The previous {@code CircuitBreakerOpen} nested class here asserted
 * that {@code CallNotPermittedException} is not a
 * {@code RestClientException} — a type-hierarchy check used to justify
 * the (broken) design of catching {@code Exception} broadly "just in
 * case". Removed: it never exercised a real circuit breaker (no Spring
 * proxy in this test setup either) and gave false confidence in a design
 * that was actually the bug.
 */
@DisplayName("IncidentAckClient")
class IncidentAckClientTest {

    private WireMockServer wireMock;
    private IncidentAckClient client;
    private ServiceTokenProvider serviceTokenProvider;

    private static final UUID INCIDENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String TENANT_ID = "test-tenant";

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        serviceTokenProvider = mock(ServiceTokenProvider.class);
        given(serviceTokenProvider.getToken()).willReturn("test-token");

        // HTTP/1.1 only — WireMock standalone does not support HTTP/2
        final HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        final RestClient restClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();

        client = new IncidentAckClient(
                restClient,
                serviceTokenProvider,
                "http://localhost:" + wireMock.port()
        );
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    // ── acknowledgeIncident ───────────────────────────────────────────────

    @Nested
    @DisplayName("acknowledgeIncident")
    class AcknowledgeIncident {

        @Test
        @DisplayName("returns true on HTTP 200")
        void returnsTrueOnSuccess() {
            wireMock.stubFor(patch(urlPathEqualTo(
                    "/api/v1/incidents/" + INCIDENT_ID + "/status"))
                    .willReturn(aResponse().withStatus(200)));

            assertThat(client.acknowledgeIncident(INCIDENT_ID, TENANT_ID, USER_ID))
                    .isTrue();
        }

        @Test
        @DisplayName("propagates on HTTP 500 — no Spring proxy in this test to redirect to the fallback")
        void throwsOnServerError() {
            wireMock.stubFor(patch(urlPathEqualTo(
                    "/api/v1/incidents/" + INCIDENT_ID + "/status"))
                    .willReturn(aResponse().withStatus(500)));

            assertThatThrownBy(() ->
                    client.acknowledgeIncident(INCIDENT_ID, TENANT_ID, USER_ID))
                    .isInstanceOf(RestClientException.class);
        }

        @Test
        @DisplayName("sends PATCH to correct URL path")
        void sendsToCorrectUrl() {
            wireMock.stubFor(patch(urlPathEqualTo(
                    "/api/v1/incidents/" + INCIDENT_ID + "/status"))
                    .willReturn(aResponse().withStatus(200)));

            client.acknowledgeIncident(INCIDENT_ID, TENANT_ID, USER_ID);

            wireMock.verify(1, patchRequestedFor(
                    urlPathEqualTo("/api/v1/incidents/" + INCIDENT_ID + "/status")));
        }

        @Test
        @DisplayName("propagates on connection refused")
        void throwsOnConnectionRefused() {
            wireMock.stop();

            assertThatThrownBy(() ->
                    client.acknowledgeIncident(INCIDENT_ID, TENANT_ID, USER_ID))
                    .isInstanceOf(RestClientException.class);

            wireMock.start();
        }
    }

    @Nested
    @DisplayName("acknowledgeIncidentFallback")
    class AcknowledgeIncidentFallback {

        @Test
        @DisplayName("returns false regardless of the exception it's given — " +
                "this method did not exist before this fix")
        void alwaysReturnsFalse() {
            assertThat(client.acknowledgeIncidentFallback(
                    INCIDENT_ID, TENANT_ID, USER_ID, new RuntimeException("boom")))
                    .isFalse();
        }
    }
}