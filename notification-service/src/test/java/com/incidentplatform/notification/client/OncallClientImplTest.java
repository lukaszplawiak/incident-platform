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

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link OncallClientImpl} using WireMock.
 *
 * <p>RestClient fluent API uses overloaded methods with generics that
 * Mockito cannot stub reliably — WireMock provides a real HTTP server
 * so production code runs as-is without any fluent API mocking.
 */
@DisplayName("OncallClientImpl")
class OncallClientImplTest {

    private WireMockServer wireMock;
    private OncallClientImpl client;

    private static final String TENANT_ID = "test-tenant";
    private static final String SLACK_USER_ID = "U0123456789";

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        final ServiceTokenProvider tokenProvider = mock(ServiceTokenProvider.class);
        given(tokenProvider.getToken()).willReturn("test-token");

        final HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        client = new OncallClientImpl(
                RestClient.builder()
                        .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                        .build(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                tokenProvider,
                "http://localhost:" + wireMock.port()
        );
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    // ── getCurrentOncall ──────────────────────────────────────────────────

    @Nested
    @DisplayName("getCurrentOncall")
    class GetCurrentOncall {

        @Test
        @DisplayName("returns fully parsed OncallInfo on success")
        void returnsParsedOncallInfo() {
            wireMock.stubFor(get(urlPathEqualTo("/api/v1/oncall/current"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {
                                      "userId": "user-1",
                                      "userName": "Jan Kowalski",
                                      "email": "jan@example.com",
                                      "phone": "+48100200300",
                                      "slackUserId": "U0123456789",
                                      "role": "PRIMARY"
                                    }
                                    """)));

            final Optional<OncallClient.OncallInfo> result =
                    client.getCurrentOncall(TENANT_ID, "PRIMARY");

            assertThat(result).isPresent();
            final OncallClient.OncallInfo info = result.get();
            assertThat(info.userId()).isEqualTo("user-1");
            assertThat(info.userName()).isEqualTo("Jan Kowalski");
            assertThat(info.email()).isEqualTo("jan@example.com");
            assertThat(info.phone()).isEqualTo("+48100200300");
            assertThat(info.slackUserId()).isEqualTo("U0123456789");
            assertThat(info.role()).isEqualTo("PRIMARY");
        }

        @Test
        @DisplayName("returns empty on HTTP 404")
        void returnsEmptyOnNotFound() {
            wireMock.stubFor(get(urlPathEqualTo("/api/v1/oncall/current"))
                    .willReturn(aResponse().withStatus(404)));

            assertThat(client.getCurrentOncall(TENANT_ID, "PRIMARY")).isEmpty();
        }

        @Test
        @DisplayName("returns empty on HTTP 500")
        void returnsEmptyOnServerError() {
            wireMock.stubFor(get(urlPathEqualTo("/api/v1/oncall/current"))
                    .willReturn(aResponse().withStatus(500)));

            assertThat(client.getCurrentOncall(TENANT_ID, "PRIMARY")).isEmpty();
        }

        @Test
        @DisplayName("returns empty when response body is empty")
        void returnsEmptyOnEmptyBody() {
            wireMock.stubFor(get(urlPathEqualTo("/api/v1/oncall/current"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("")));

            assertThat(client.getCurrentOncall(TENANT_ID, "PRIMARY")).isEmpty();
        }

        @Test
        @DisplayName("returns empty on connection refused")
        void returnsEmptyOnConnectionRefused() {
            wireMock.stop();

            assertThat(client.getCurrentOncall(TENANT_ID, "PRIMARY")).isEmpty();

            wireMock.start();
        }
    }

    // ── findBySlackUserId ─────────────────────────────────────────────────

    @Nested
    @DisplayName("findBySlackUserId")
    class FindBySlackUserId {

        @Test
        @DisplayName("returns OncallInfo with userId and slackUserId on success")
        void returnsMappedOncallInfo() {
            wireMock.stubFor(get(urlPathMatching(
                    "/api/v1/oncall/by-slack/.*"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {
                                      "userId": "user-42",
                                      "userName": "Anna Nowak",
                                      "slackUserId": "U9876543210"
                                    }
                                    """)));

            final Optional<OncallClient.OncallInfo> result =
                    client.findBySlackUserId(TENANT_ID, SLACK_USER_ID);

            assertThat(result).isPresent();
            final OncallClient.OncallInfo info = result.get();
            assertThat(info.userId()).isEqualTo("user-42");
            assertThat(info.userName()).isEqualTo("Anna Nowak");
            assertThat(info.slackUserId()).isEqualTo("U9876543210");
            // email and phone not mapped in findBySlackUserId
            assertThat(info.email()).isNull();
            assertThat(info.phone()).isNull();
        }

        @Test
        @DisplayName("returns empty on HTTP 404")
        void returnsEmptyOnNotFound() {
            wireMock.stubFor(get(urlPathMatching("/api/v1/oncall/by-slack/.*"))
                    .willReturn(aResponse().withStatus(404)));

            assertThat(client.findBySlackUserId(TENANT_ID, SLACK_USER_ID)).isEmpty();
        }

        @Test
        @DisplayName("returns empty when response body is empty")
        void returnsEmptyOnEmptyBody() {
            wireMock.stubFor(get(urlPathMatching("/api/v1/oncall/by-slack/.*"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("")));

            assertThat(client.findBySlackUserId(TENANT_ID, SLACK_USER_ID)).isEmpty();
        }

        @Test
        @DisplayName("returns empty on connection refused")
        void returnsEmptyOnConnectionRefused() {
            wireMock.stop();

            assertThat(client.findBySlackUserId(TENANT_ID, SLACK_USER_ID)).isEmpty();

            wireMock.start();
        }
    }

    // ── OncallInfo helper methods ─────────────────────────────────────────

    @Nested
    @DisplayName("OncallInfo")
    class OncallInfoTest {

        @Test
        @DisplayName("hasDm() returns true when slackUserId is present")
        void hasDmTrue() {
            assertThat(info(null, "U123").hasDm()).isTrue();
        }

        @Test
        @DisplayName("hasDm() returns false when slackUserId is null")
        void hasDmFalseNull() {
            assertThat(info(null, null).hasDm()).isFalse();
        }

        @Test
        @DisplayName("hasDm() returns false when slackUserId is blank")
        void hasDmFalseBlank() {
            assertThat(info(null, "  ").hasDm()).isFalse();
        }

        @Test
        @DisplayName("hasSms() returns true when phone is present")
        void hasSmsTrue() {
            assertThat(info("+48100200300", null).hasSms()).isTrue();
        }

        @Test
        @DisplayName("hasSms() returns false when phone is null")
        void hasSmsFalseNull() {
            assertThat(info(null, null).hasSms()).isFalse();
        }

        private OncallClient.OncallInfo info(String phone, String slackUserId) {
            return new OncallClient.OncallInfo(
                    "u1", "Jan", null, phone, slackUserId, "PRIMARY");
        }
    }
}