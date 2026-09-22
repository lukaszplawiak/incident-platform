package com.incidentplatform.notification.client;

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
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link OncallClientImpl} using WireMock.
 *
 * <p>RestClient fluent API uses overloaded methods with generics that
 * Mockito cannot stub reliably — WireMock provides a real HTTP server
 * so production code runs as-is without any fluent API mocking.
 *
 * <h2>Why the error-path tests changed from "returns empty" to "throws"</h2>
 * {@code client} here is constructed via plain {@code new
 * OncallClientImpl(...)} — there is no Spring context and therefore no
 * Resilience4j AOP proxy wrapping these calls in this test. Before the
 * class's own fix (see its Javadoc), an internal try/catch swallowed
 * every failure inline and returned {@code Optional.empty()} directly —
 * which is what these tests previously asserted, but that also meant
 * they were only exercising plain business logic, never actually proving
 * anything about circuit-breaker behavior (there was no proxy present to
 * behave incorrectly in the first place). Now that the production method
 * lets real failures propagate (so the *real*, Spring-managed proxy can
 * see and record them), calling the bare object directly means those
 * same failures surface as thrown exceptions here instead of a silently
 * swallowed empty result. The corresponding fallback methods — what
 * actually produces the graceful {@code Optional.empty()} in production,
 * once Resilience4j's proxy catches the propagated failure — are tested
 * directly and separately below.
 */
@DisplayName("OncallClientImpl")
class OncallClientImplTest {

    private WireMockServer wireMock;
    private OncallClientImpl client;

    private SimpleMeterRegistry meterRegistry;

    private static final String TENANT_ID = "test-tenant";
    private static final String SLACK_USER_ID = "U0123456789";

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        final ServiceTokenProvider tokenProvider = mock(ServiceTokenProvider.class);
        // pinned to the tenant AND the target service: with any() a client that minted the
        // token for the wrong tenant or audience would still pass every test here
        given(tokenProvider.getToken(TENANT_ID, ServiceNames.ONCALL_SERVICE))
                .willReturn("test-token");
        meterRegistry = new SimpleMeterRegistry();

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
                new ClientFallbackMetrics(meterRegistry),
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
                    client.getCurrentOncall(TENANT_ID, null, "PRIMARY");

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
        @DisplayName("returns empty when response body is empty (not an error — handled inline, not via fallback)")
        void returnsEmptyOnEmptyBody() {
            wireMock.stubFor(get(urlPathEqualTo("/api/v1/oncall/current"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("")));

            assertThat(client.getCurrentOncall(TENANT_ID, null, "PRIMARY")).isEmpty();
        }

        @Test
        @DisplayName("propagates on HTTP 404 — no Spring proxy in this test to redirect to the fallback")
        void throwsOnNotFound() {
            wireMock.stubFor(get(urlPathEqualTo("/api/v1/oncall/current"))
                    .willReturn(aResponse().withStatus(404)));

            assertThatThrownBy(() -> client.getCurrentOncall(TENANT_ID, null, "PRIMARY"))
                    .isInstanceOf(RestClientException.class);
        }

        @Test
        @DisplayName("propagates on HTTP 500")
        void throwsOnServerError() {
            wireMock.stubFor(get(urlPathEqualTo("/api/v1/oncall/current"))
                    .willReturn(aResponse().withStatus(500)));

            assertThatThrownBy(() -> client.getCurrentOncall(TENANT_ID, null, "PRIMARY"))
                    .isInstanceOf(RestClientException.class);
        }

        @Test
        @DisplayName("propagates on connection refused")
        void throwsOnConnectionRefused() {
            wireMock.stop();

            assertThatThrownBy(() -> client.getCurrentOncall(TENANT_ID, null, "PRIMARY"))
                    .isInstanceOf(RestClientException.class);

            wireMock.start();
        }

        /**
         * Backlog #0-12: no existing test in this file asserted on query
         * parameters at all. These two are what actually proves the fix —
         * that a non-null {@code teamId} reaches oncall-service on the wire,
         * and that a null one is omitted rather than rendered as a literal
         * {@code teamId=null} (which oncall-service's {@code UUID} param
         * would fail to parse).
         */
        @Test
        @DisplayName("sends teamId as a query param when given (backlog #0-12)")
        void sendsTeamIdWhenPresent() {
            final UUID teamId = UUID.randomUUID();
            wireMock.stubFor(get(urlPathEqualTo("/api/v1/oncall/current"))
                    .willReturn(aResponse().withStatus(204)));

            client.getCurrentOncall(TENANT_ID, teamId, "PRIMARY");

            wireMock.verify(getRequestedFor(urlPathEqualTo("/api/v1/oncall/current"))
                    .withQueryParam("teamId", equalTo(teamId.toString()))
                    .withQueryParam("role", equalTo("PRIMARY")));
        }

        @Test
        @DisplayName("omits teamId from the query string when null, instead of sending a literal 'null'")
        void omitsTeamIdWhenAbsent() {
            wireMock.stubFor(get(urlPathEqualTo("/api/v1/oncall/current"))
                    .willReturn(aResponse().withStatus(204)));

            client.getCurrentOncall(TENANT_ID, null, "PRIMARY");

            wireMock.verify(getRequestedFor(urlPathEqualTo("/api/v1/oncall/current"))
                    .withQueryParam("teamId", absent())
                    .withQueryParam("role", equalTo("PRIMARY")));
        }
    }

    // ── findCurrentByUserId (backlog #0-1) ────────────────────────────────

    @Nested
    @DisplayName("findCurrentByUserId")
    class FindCurrentByUserId {

        private static final String USER_ID = "11111111-1111-1111-1111-111111111111";
        private static final String PATH = "/api/v1/oncall/current/by-user/" + USER_ID;

        @Test
        @DisplayName("returns fully parsed OncallInfo, and sends the tenant's token and headers")
        void returnsParsedOncallInfo() {
            wireMock.stubFor(get(urlPathEqualTo(PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {
                                      "userId": "11111111-1111-1111-1111-111111111111",
                                      "userName": "Sam Secondary",
                                      "email": "sam@example.com",
                                      "phone": "+48100200301",
                                      "slackUserId": "U0987654321",
                                      "role": "SECONDARY"
                                    }
                                    """)));

            final Optional<OncallClient.OncallInfo> result =
                    client.findCurrentByUserId(TENANT_ID, USER_ID);

            assertThat(result).isPresent();
            final OncallClient.OncallInfo info = result.get();
            assertThat(info.userId()).isEqualTo(USER_ID);
            assertThat(info.email()).isEqualTo("sam@example.com");
            assertThat(info.phone()).isEqualTo("+48100200301");
            assertThat(info.slackUserId()).isEqualTo("U0987654321");
            assertThat(info.role()).isEqualTo("SECONDARY");
            wireMock.verify(getRequestedFor(urlPathEqualTo(PATH))
                    .withHeader("Authorization", equalTo("Bearer test-token"))
                    .withHeader("X-Tenant-Id", equalTo(TENANT_ID)));
        }

        @Test
        @DisplayName("encodes the user id as a single path segment — '/' or '?' cannot change the path")
        void encodesUserIdAsOneSegment() {
            wireMock.stubFor(get(urlPathEqualTo("/api/v1/oncall/current/by-user/a%2Fb%3Fc"))
                    .willReturn(aResponse().withStatus(204)));

            assertThat(client.findCurrentByUserId(TENANT_ID, "a/b?c")).isEmpty();

            wireMock.verify(getRequestedFor(
                    urlPathEqualTo("/api/v1/oncall/current/by-user/a%2Fb%3Fc")));
        }

        @Test
        @DisplayName("ignores a response for a different user than the one asked for")
        void ignoresResponseForAnotherUser() {
            wireMock.stubFor(get(urlPathEqualTo(PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {
                                      "userId": "99999999-9999-9999-9999-999999999999",
                                      "userName": "Somebody Else",
                                      "email": "else@example.com",
                                      "phone": "+48100200399",
                                      "slackUserId": "UELSE",
                                      "role": "SECONDARY"
                                    }
                                    """)));

            assertThat(client.findCurrentByUserId(TENANT_ID, USER_ID)).isEmpty();
        }

        @Test
        @DisplayName("returns empty on 204 — the user is not on call right now")
        void returnsEmptyOnNoContent() {
            wireMock.stubFor(get(urlPathEqualTo(PATH))
                    .willReturn(aResponse().withStatus(204)));

            assertThat(client.findCurrentByUserId(TENANT_ID, USER_ID)).isEmpty();
        }

        @Test
        @DisplayName("propagates on HTTP 403 — a rejected token must reach the fallback, not look like 'not on call'")
        void throwsOnForbidden() {
            wireMock.stubFor(get(urlPathEqualTo(PATH))
                    .willReturn(aResponse().withStatus(403)));

            assertThatThrownBy(() -> client.findCurrentByUserId(TENANT_ID, USER_ID))
                    .isInstanceOf(RestClientException.class);
        }

        @Test
        @DisplayName("propagates on HTTP 500")
        void throwsOnServerError() {
            wireMock.stubFor(get(urlPathEqualTo(PATH))
                    .willReturn(aResponse().withStatus(500)));

            assertThatThrownBy(() -> client.findCurrentByUserId(TENANT_ID, USER_ID))
                    .isInstanceOf(RestClientException.class);
        }

        @Test
        @DisplayName("propagates on connection refused")
        void throwsOnConnectionRefused() {
            wireMock.stop();

            assertThatThrownBy(() -> client.findCurrentByUserId(TENANT_ID, USER_ID))
                    .isInstanceOf(RestClientException.class);
        }

        @Test
        @DisplayName("propagates a malformed response body as a parsing failure")
        void throwsOnMalformedBody() {
            wireMock.stubFor(get(urlPathEqualTo(PATH))
                    .willReturn(aResponse().withStatus(200).withBody("not json")));

            assertThatThrownBy(() -> client.findCurrentByUserId(TENANT_ID, USER_ID))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("fallback counts the failure (401/403 as reason=auth) and throws — it does not fail open (backlog #0-19)")
        void fallbackThrowsAndCounts() {
            assertThatThrownBy(() -> client.findCurrentByUserIdFallback(TENANT_ID, USER_ID,
                    HttpClientErrorException.create(HttpStatus.FORBIDDEN, "f",
                            HttpHeaders.EMPTY, new byte[0], null)))
                    .isInstanceOf(OncallLookupUnavailableException.class);

            assertThat(meterRegistry.counter(ClientFallbackMetrics.METRIC_NAME,
                    "client", "oncall", "target", "oncall-service",
                    "reason", "auth").count()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("service token on the wire")
    class ServiceTokenOnTheWire {

        @Test
        @DisplayName("getCurrentOncall sends the token minted for this tenant and oncall-service")
        void currentOncallSendsTenantToken() {
            wireMock.stubFor(get(urlPathEqualTo("/api/v1/oncall/current"))
                    .willReturn(aResponse().withStatus(204)));

            client.getCurrentOncall(TENANT_ID, null, "PRIMARY");

            wireMock.verify(getRequestedFor(urlPathEqualTo("/api/v1/oncall/current"))
                    .withHeader("Authorization", equalTo("Bearer test-token"))
                    .withHeader("X-Tenant-Id", equalTo(TENANT_ID)));
        }

        @Test
        @DisplayName("findBySlackUserId sends the token minted for this tenant and oncall-service")
        void bySlackSendsTenantToken() {
            wireMock.stubFor(get(urlPathMatching("/api/v1/oncall/by-slack/.*"))
                    .willReturn(aResponse().withStatus(204)));

            client.findBySlackUserId(TENANT_ID, SLACK_USER_ID);

            wireMock.verify(getRequestedFor(urlPathMatching("/api/v1/oncall/by-slack/.*"))
                    .withHeader("Authorization", equalTo("Bearer test-token"))
                    .withHeader("X-Tenant-Id", equalTo(TENANT_ID)));
        }
    }

    @Nested
    @DisplayName("fallbacks are counted")
    class FallbacksAreCounted {

        private double count(String reason) {
            return meterRegistry.counter(ClientFallbackMetrics.METRIC_NAME,
                    "client", "oncall", "target", "oncall-service",
                    "reason", reason).count();
        }

        @Test
        @DisplayName("getCurrentOncallFallback counts a 401 as reason=auth")
        void currentOncallCountsAuth() {
            assertThatThrownBy(() -> client.getCurrentOncallFallback(TENANT_ID, null, "PRIMARY",
                    HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "u",
                            HttpHeaders.EMPTY, new byte[0], null)))
                    .isInstanceOf(OncallLookupUnavailableException.class);

            assertThat(count("auth")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("findBySlackUserIdFallback counts a 403 as reason=auth")
        void bySlackCountsAuth() {
            client.findBySlackUserIdFallback(TENANT_ID, SLACK_USER_ID,
                    HttpClientErrorException.create(HttpStatus.FORBIDDEN, "f",
                            HttpHeaders.EMPTY, new byte[0], null));

            assertThat(count("auth")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a generic failure is counted as reason=other")
        void genericFailureCountsOther() {
            assertThatThrownBy(() -> client.getCurrentOncallFallback(
                    TENANT_ID, null, "PRIMARY", new RuntimeException("boom")))
                    .isInstanceOf(OncallLookupUnavailableException.class);

            assertThat(count("other")).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("getCurrentOncallFallback")
    class GetCurrentOncallFallback {

        @Test
        @DisplayName("throws OncallLookupUnavailableException — a failed lookup is not 'nobody on call' (backlog #0-19)")
        void throwsLookupUnavailable() {
            final RuntimeException cause = new RuntimeException("boom");

            assertThatThrownBy(() -> client.getCurrentOncallFallback(
                    TENANT_ID, null, "PRIMARY", cause))
                    .isInstanceOf(OncallLookupUnavailableException.class)
                    .hasCause(cause);
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
        @DisplayName("returns empty when response body is empty (not an error — handled inline, not via fallback)")
        void returnsEmptyOnEmptyBody() {
            wireMock.stubFor(get(urlPathMatching("/api/v1/oncall/by-slack/.*"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("")));

            assertThat(client.findBySlackUserId(TENANT_ID, SLACK_USER_ID)).isEmpty();
        }

        @Test
        @DisplayName("propagates on HTTP 404")
        void throwsOnNotFound() {
            wireMock.stubFor(get(urlPathMatching("/api/v1/oncall/by-slack/.*"))
                    .willReturn(aResponse().withStatus(404)));

            assertThatThrownBy(() -> client.findBySlackUserId(TENANT_ID, SLACK_USER_ID))
                    .isInstanceOf(RestClientException.class);
        }

        @Test
        @DisplayName("propagates on connection refused")
        void throwsOnConnectionRefused() {
            wireMock.stop();

            assertThatThrownBy(() -> client.findBySlackUserId(TENANT_ID, SLACK_USER_ID))
                    .isInstanceOf(RestClientException.class);

            wireMock.start();
        }
    }

    @Nested
    @DisplayName("findBySlackUserIdFallback")
    class FindBySlackUserIdFallback {

        @Test
        @DisplayName("returns empty regardless of the exception it's given")
        void alwaysReturnsEmpty() {
            assertThat(client.findBySlackUserIdFallback(
                    TENANT_ID, SLACK_USER_ID, new RuntimeException("boom")))
                    .isEmpty();
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