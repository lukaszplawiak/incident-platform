package com.incidentplatform.ingestion.apikey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.incidentplatform.shared.observability.ClientFallbackMetrics;
import com.incidentplatform.shared.security.ServiceNames;
import com.incidentplatform.shared.security.ServiceTokenProvider;
import com.incidentplatform.shared.security.TokenPurposes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link ApiKeyIntrospectionClientImpl} against WireMock — same
 * approach as notification-service's {@code SlackWorkspaceClientImplTest}.
 *
 * <p>The client is built with {@code new}, so there is no Resilience4j proxy:
 * a failure surfaces as the thrown exception, and the fallback is called
 * directly. {@link ApiKeyIntrospectionClientResilienceTest} checks that the
 * proxy really routes failures to it.
 */
@DisplayName("ApiKeyIntrospectionClientImpl")
class ApiKeyIntrospectionClientImplTest {

    private static final String PATH = "/api/v1/internal/api-keys/introspect";
    private static final String KEY_HASH = "a".repeat(64);

    private WireMockServer wireMock;
    private SimpleMeterRegistry meterRegistry;
    private ApiKeyIntrospectionClientImpl client;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        final ServiceTokenProvider tokenProvider = mock(ServiceTokenProvider.class);
        // Pinned to the purpose AND auth-service's audience: a tenant-bound
        // service token, or one for the wrong audience, would be rejected there.
        given(tokenProvider.getPurposeToken(
                TokenPurposes.API_KEY_INTROSPECTION, ServiceNames.AUTH_SERVICE))
                .willReturn("purpose-token");
        meterRegistry = new SimpleMeterRegistry();

        final HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        client = new ApiKeyIntrospectionClientImpl(
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

    private void stubAnswer(int status, String body) {
        wireMock.stubFor(post(urlPathEqualTo(PATH))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    @Nested
    @DisplayName("introspect")
    class Introspect {

        @Test
        @DisplayName("an active key is parsed with its tenant, team, scopes and expiry")
        void activeKeyIsParsed() {
            final UUID keyId = UUID.randomUUID();
            final UUID teamId = UUID.randomUUID();
            stubAnswer(200, """
                    {"active": true, "keyId": "%s", "tenantId": "platform-operator",
                     "teamId": "%s", "scopes": ["alerts:ingest"],
                     "expiresAt": "2030-01-01T00:00:00Z"}
                    """.formatted(keyId, teamId));

            final Optional<IntrospectedApiKey> result = client.introspect(KEY_HASH);

            assertThat(result).contains(new IntrospectedApiKey(keyId, "platform-operator", teamId,
                    List.of("alerts:ingest"), Instant.parse("2030-01-01T00:00:00Z")));
        }

        @Test
        @DisplayName("a key with no team and no expiry has null teamId and expiresAt")
        void absentOptionalFields() {
            final UUID keyId = UUID.randomUUID();
            stubAnswer(200, """
                    {"active": true, "keyId": "%s", "tenantId": "acme", "scopes": []}
                    """.formatted(keyId));

            final IntrospectedApiKey key = client.introspect(KEY_HASH).orElseThrow();

            assertThat(key.teamId()).isNull();
            assertThat(key.expiresAt()).isNull();
            assertThat(key.scopes()).isEmpty();
        }

        @Test
        @DisplayName("{\"active\":false} is a normal answer: empty, not an exception")
        void inactiveKeyIsEmpty() {
            stubAnswer(200, "{\"active\": false}");

            assertThat(client.introspect(KEY_HASH)).isEmpty();
        }

        @Test
        @DisplayName("sends only the hash, with the purpose token, never the raw key")
        void sendsHashWithPurposeToken() {
            stubAnswer(200, "{\"active\": false}");

            client.introspect(KEY_HASH);

            wireMock.verify(postRequestedFor(urlPathEqualTo(PATH))
                    .withHeader("Authorization", equalTo("Bearer purpose-token"))
                    .withRequestBody(equalToJson("{\"keyHash\": \"" + KEY_HASH + "\"}")));
        }

        @Test
        @DisplayName("a 5xx propagates (the proxy turns it into the fallback)")
        void serverErrorPropagates() {
            stubAnswer(503, "");

            assertThatThrownBy(() -> client.introspect(KEY_HASH))
                    .isInstanceOf(HttpServerErrorException.class);
        }

        @Test
        @DisplayName("a 401 from auth-service propagates — our token was rejected, not the key")
        void unauthorizedPropagates() {
            stubAnswer(401, "");

            assertThatThrownBy(() -> client.introspect(KEY_HASH))
                    .isInstanceOf(HttpClientErrorException.Unauthorized.class);
        }

        @Test
        @DisplayName("an unparseable body is a failure, not an inactive key")
        void unparseableBodyIsFailure() {
            stubAnswer(200, "not json {");

            assertThatThrownBy(() -> client.introspect(KEY_HASH))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("introspectFallback")
    class Fallback {

        @Test
        @DisplayName("throws ApiKeyIntrospectionUnavailableException and records the fallback metric")
        void throwsUnavailableAndRecordsMetric() {
            final HttpServerErrorException cause =
                    HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE,
                            "down", null, null, null);

            assertThatThrownBy(() -> client.introspectFallback(KEY_HASH, cause))
                    .isInstanceOf(ApiKeyIntrospectionUnavailableException.class)
                    .hasCause(cause);

            assertThat(meterRegistry.counter(ClientFallbackMetrics.METRIC_NAME,
                    "client", "api-key-introspection", "target", ServiceNames.AUTH_SERVICE,
                    "reason", "server_error").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a rejected purpose token is recorded as reason=auth (misconfiguration)")
        void rejectedTokenIsReasonAuth() {
            final HttpClientErrorException cause =
                    HttpClientErrorException.create(HttpStatus.UNAUTHORIZED,
                            "no", null, null, null);

            assertThatThrownBy(() -> client.introspectFallback(KEY_HASH, cause))
                    .isInstanceOf(ApiKeyIntrospectionUnavailableException.class);

            assertThat(meterRegistry.counter(ClientFallbackMetrics.METRIC_NAME,
                    "client", "api-key-introspection", "target", ServiceNames.AUTH_SERVICE,
                    "reason", "auth").count()).isEqualTo(1.0);
        }
    }
}
