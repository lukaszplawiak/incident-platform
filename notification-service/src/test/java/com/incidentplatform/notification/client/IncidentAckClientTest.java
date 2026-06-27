package com.incidentplatform.notification.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.incidentplatform.shared.security.ServiceTokenProvider;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
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
        @DisplayName("returns false on HTTP 500")
        void returnsFalseOnServerError() {
            wireMock.stubFor(patch(urlPathEqualTo(
                    "/api/v1/incidents/" + INCIDENT_ID + "/status"))
                    .willReturn(aResponse().withStatus(500)));

            assertThat(client.acknowledgeIncident(INCIDENT_ID, TENANT_ID, USER_ID))
                    .isFalse();
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
        @DisplayName("returns false on connection refused")
        void returnsFalseOnConnectionRefused() {
            wireMock.stop();

            assertThat(client.acknowledgeIncident(INCIDENT_ID, TENANT_ID, USER_ID))
                    .isFalse();

            wireMock.start();
        }
    }

    // ── catch(Exception e) handles CallNotPermittedException ──────────────

    @Nested
    @DisplayName("circuit breaker open")
    class CircuitBreakerOpen {

        @Test
        @DisplayName("CallNotPermittedException is not RestClientException — caught by catch(Exception e)")
        void callNotPermittedExceptionIsNotRestClientException() {
            // Verify the exception type hierarchy — this is what makes
            // catch(Exception e) necessary in addition to catch(RestClientException e)
            final CircuitBreaker cb = CircuitBreaker.of("test",
                    CircuitBreakerConfig.ofDefaults());
            final CallNotPermittedException ex =
                    CallNotPermittedException.createCallNotPermittedException(cb);

            assertThat(ex)
                    .isNotInstanceOf(
                            org.springframework.web.client.RestClientException.class);
            assertThat(ex)
                    .isInstanceOf(RuntimeException.class);
        }
    }
}