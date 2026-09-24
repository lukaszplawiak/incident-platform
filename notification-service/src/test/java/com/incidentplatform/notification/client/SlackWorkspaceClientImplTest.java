package com.incidentplatform.notification.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.incidentplatform.shared.observability.ClientFallbackMetrics;
import com.incidentplatform.shared.security.ServiceNames;
import com.incidentplatform.shared.security.ServiceTokenProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link SlackWorkspaceClientImpl} using WireMock — same approach as
 * {@link OncallClientImplTest}.
 *
 * <p>{@code client} is built with plain {@code new}, so there is no Resilience4j
 * proxy here: a real failure surfaces as the thrown exception, and the fallback
 * is tested by calling it directly. That the proxy actually routes a failure to
 * the fallback (the thing the first implementation got wrong) is covered by
 * {@link SlackWorkspaceClientResilienceTest}, which runs a Spring context.
 */
@DisplayName("SlackWorkspaceClientImpl")
class SlackWorkspaceClientImplTest {

    private static final String TENANT_ID = "test-tenant";
    private static final String PATH = "/api/v1/internal/slack-workspace";

    private WireMockServer wireMock;
    private SimpleMeterRegistry meterRegistry;
    private SlackWorkspaceClientImpl client;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        final ServiceTokenProvider tokenProvider = mock(ServiceTokenProvider.class);
        // Pinned to the tenant AND auth-service's audience: with any() a client
        // minting the token for the wrong tenant, or for its own name instead of
        // the target's, would still pass — and auth-service would reject it.
        given(tokenProvider.getToken(TENANT_ID, ServiceNames.AUTH_SERVICE))
                .willReturn("test-token");
        meterRegistry = new SimpleMeterRegistry();

        final HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        client = new SlackWorkspaceClientImpl(
                RestClient.builder()
                        .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                        .build(),
                new ObjectMapper(),
                tokenProvider,
                new ClientFallbackMetrics(meterRegistry),
                "http://localhost:" + wireMock.port());
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Nested
    @DisplayName("getWorkspace")
    class GetWorkspace {

        @Test
        @DisplayName("returns the parsed workspace on 200")
        void returnsParsedWorkspace() {
            final UUID teamId = UUID.randomUUID();
            wireMock.stubFor(get(urlPathEqualTo(PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {
                                      "botToken": "xoxb-tenant",
                                      "defaultChannel": "#acme-incidents",
                                      "broadcastEnabled": true,
                                      "teamId": "%s"
                                    }
                                    """.formatted(teamId))));

            final Optional<SlackWorkspaceClient.SlackWorkspaceInfo> result =
                    client.getWorkspace(TENANT_ID);

            assertThat(result).contains(new SlackWorkspaceClient.SlackWorkspaceInfo(
                    "xoxb-tenant", "#acme-incidents", true, teamId));
        }

        @Test
        @DisplayName("maps absent optional fields to null/false")
        void mapsAbsentOptionalFields() {
            wireMock.stubFor(get(urlPathEqualTo(PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"botToken\": \"xoxb-tenant\"}")));

            assertThat(client.getWorkspace(TENANT_ID)).contains(
                    new SlackWorkspaceClient.SlackWorkspaceInfo("xoxb-tenant", null, false, null));
        }

        @Test
        @DisplayName("404 is a normal answer — no workspace — returned as empty, not thrown")
        void notFoundIsEmpty() {
            wireMock.stubFor(get(urlPathEqualTo(PATH))
                    .willReturn(aResponse().withStatus(404)));

            assertThat(client.getWorkspace(TENANT_ID)).isEmpty();
        }

        @Test
        @DisplayName("propagates a 5xx so the circuit breaker can see it")
        void propagatesServerError() {
            wireMock.stubFor(get(urlPathEqualTo(PATH))
                    .willReturn(aResponse().withStatus(503)));

            assertThatThrownBy(() -> client.getWorkspace(TENANT_ID))
                    .isInstanceOf(HttpServerErrorException.class);
        }

        @Test
        @DisplayName("propagates a 401 (rejected service token) — never read as 'no workspace'")
        void propagatesUnauthorized() {
            wireMock.stubFor(get(urlPathEqualTo(PATH))
                    .willReturn(aResponse().withStatus(401)));

            assertThatThrownBy(() -> client.getWorkspace(TENANT_ID))
                    .isInstanceOf(HttpClientErrorException.Unauthorized.class);
        }

        @Test
        @DisplayName("propagates a malformed body as a parsing failure")
        void propagatesMalformedBody() {
            wireMock.stubFor(get(urlPathEqualTo(PATH))
                    .willReturn(aResponse().withStatus(200).withBody("not json")));

            assertThatThrownBy(() -> client.getWorkspace(TENANT_ID))
                    .isInstanceOf(SlackWorkspaceClientImpl.SlackWorkspaceResponseParsingException.class);
        }

        @Test
        @DisplayName("sends the service token minted for this tenant and auth-service")
        void sendsTenantTokenForAuthService() {
            wireMock.stubFor(get(urlPathEqualTo(PATH))
                    .willReturn(aResponse().withStatus(404)));

            client.getWorkspace(TENANT_ID);

            wireMock.verify(getRequestedFor(urlPathEqualTo(PATH))
                    .withHeader("Authorization", equalTo("Bearer test-token"))
                    .withHeader("X-Tenant-Id", equalTo(TENANT_ID)));
        }
    }

    @Nested
    @DisplayName("getWorkspaceFallback")
    class GetWorkspaceFallback {

        private double count(String reason) {
            return meterRegistry.counter(ClientFallbackMetrics.METRIC_NAME,
                    "client", "slack-workspace", "target", "auth-service",
                    "reason", reason).count();
        }

        @Test
        @DisplayName("throws SlackWorkspaceLookupUnavailableException with the cause — an outage is not 'no workspace'")
        void throwsLookupUnavailable() {
            final RuntimeException cause = new RuntimeException("boom");

            assertThatThrownBy(() -> client.getWorkspaceFallback(TENANT_ID, cause))
                    .isInstanceOf(SlackWorkspaceLookupUnavailableException.class)
                    .hasCause(cause);
            assertThat(count("other")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("counts a 403 as reason=auth — a token misconfiguration, not an outage")
        void countsAuth() {
            assertThatThrownBy(() -> client.getWorkspaceFallback(TENANT_ID,
                    HttpClientErrorException.create(HttpStatus.FORBIDDEN, "f",
                            HttpHeaders.EMPTY, new byte[0], null)))
                    .isInstanceOf(SlackWorkspaceLookupUnavailableException.class);

            assertThat(count("auth")).isEqualTo(1.0);
        }
    }
}
