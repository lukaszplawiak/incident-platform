package com.incidentplatform.notification.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.incidentplatform.notification.client.SlackWorkspaceClient;
import com.incidentplatform.notification.client.SlackWorkspaceLookupUnavailableException;
import com.incidentplatform.notification.config.NotificationChannelProperties;
import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.notification.slack.SlackMessageStore;
import com.incidentplatform.shared.domain.Severity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * Regression test for the bug documented in {@link SlackNotificationChannel#send}:
 * the Slack message {@code ts} returned by {@code postIncidentMessage} (then {@code sendWithAckButton}) was
 * previously discarded entirely — {@link SlackMessageStore#save} was never
 * called anywhere in the codebase, so the "update every Slack message for
 * this incident after ACK" feature never worked in any deployment (not a
 * replica-scaling issue — the store was always empty, regardless of
 * replica count).
 *
 * <p>Enabled by making the Slack API base URL configurable
 * ({@code notification.channels.slack.api-base-url}) instead of a
 * hardcoded {@code https://slack.com/api} constant — this is what lets
 * WireMock (running on localhost) intercept the call at all. See
 * {@code NotificationChannelProperties.Slack}'s Javadoc for that change.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SlackNotificationChannel — send() persists ts via SlackMessageStore")
class SlackNotificationChannelSendTest {

    private WireMockServer wireMock;
    private SlackNotificationChannel channel;

    @Mock
    private SlackMessageStore messageStore;

    @Mock
    private SlackWorkspaceClient slackWorkspaceClient;

    private static final UUID INCIDENT_ID = UUID.randomUUID();
    private static final String TENANT_ID = "acme-corp";
    private static final String DEFAULT_CHANNEL = "#incidents";
    private static final String BOT_TOKEN = "xoxb-test-token";
    private static final String SLACK_TS = "1700000000.123456";

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        wireMock.stubFor(post(urlPathEqualTo("/chat.postMessage"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"ts\":\"" + SLACK_TS + "\"}")));

        channel = newChannel(true);
    }

    /**
     * @param broadcastEnabled whether every notification is also posted to the
     *                         shared channel (backlog #0-18); the tests that
     *                         predate the flag exercise it with true. Backlog
     *                         #0-21: this is now per-tenant, read via {@link
     *                         SlackWorkspaceClient} — stubbed here instead of
     *                         set on {@link NotificationChannelProperties}.
     */
    private SlackNotificationChannel newChannel(boolean broadcastEnabled) {
        final NotificationChannelProperties properties = new NotificationChannelProperties(
                new NotificationChannelProperties.Channels(
                        new NotificationChannelProperties.Email(true, "alerts@test.com"),
                        new NotificationChannelProperties.Slack(
                                true, "signing-secret", "http://localhost:" + wireMock.port()),
                        new NotificationChannelProperties.Sms(true, "+1234567890")),
                new NotificationChannelProperties.OperatorAlert("operator@test.com", null));

        // lenient: setUp() always builds a channel, but not every test sends
        // through it (isSlackUserIdPredicate) and some re-stub it via
        // newChannel(false) — strict stubs would flag both as unnecessary.
        lenient().when(slackWorkspaceClient.getWorkspace(TENANT_ID)).thenReturn(
                Optional.of(new SlackWorkspaceClient.SlackWorkspaceInfo(
                        BOT_TOKEN, DEFAULT_CHANNEL, broadcastEnabled, null)));

        // HTTP/1.1 only — WireMock standalone does not support HTTP/2, and
        // JdkClientHttpRequestFactory defaults to HTTP/2 which causes
        // RST_STREAM errors against WireMock (same fix already applied in
        // IncidentAckClientTest/OncallClientImplTest).
        final HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        return new SlackNotificationChannel(
                RestClient.builder()
                        .requestFactory(new JdkClientHttpRequestFactory(httpClient)),
                new ObjectMapper(),
                properties,
                messageStore,
                slackWorkspaceClient);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    @DisplayName("saves the ts for the default channel after a successful send")
    void savesTsForDefaultChannel() {
        // recipient is a plain email — not a Slack user ID (doesn't start
        // with "U") — so only the default-channel message is sent, no DM.
        final NotificationRequest request = buildRequest("oncall@test.com");

        channel.send(request);

        then(messageStore).should().save(
                eq(INCIDENT_ID), eq(DEFAULT_CHANNEL), eq(TENANT_ID), eq(SLACK_TS));
    }

    @Test
    @DisplayName("saves the ts for both the default channel AND the DM when recipient is a Slack user ID")
    void savesTsForBothChannelsWhenRecipientIsSlackUser() {
        final String slackUserId = "U0123456789";
        final NotificationRequest request = buildRequest(slackUserId);

        channel.send(request);

        then(messageStore).should().save(
                eq(INCIDENT_ID), eq(DEFAULT_CHANNEL), eq(TENANT_ID), eq(SLACK_TS));
        then(messageStore).should().save(
                eq(INCIDENT_ID), eq(slackUserId), eq(TENANT_ID), eq(SLACK_TS));
    }

    @Test
    @DisplayName("does not save anything when the recipient is not a Slack user ID — only one message was sent")
    void doesNotSaveDmEntryForNonSlackRecipient() {
        final NotificationRequest request = buildRequest("oncall@test.com");

        channel.send(request);

        // Exactly one save — the default channel. Never a second one for
        // a DM that was never sent.
        then(messageStore).should().save(
                eq(INCIDENT_ID), eq(DEFAULT_CHANNEL), eq(TENANT_ID), eq(SLACK_TS));
        then(messageStore).should(never()).save(
                eq(INCIDENT_ID), eq("oncall@test.com"), eq(TENANT_ID), eq(SLACK_TS));
    }

    @Test
    @DisplayName("broadcast disabled (the default): nothing is posted to the shared channel, the DM still goes out")
    void broadcastDisabledSendsOnlyTheDm() {
        final SlackNotificationChannel dmOnly = newChannel(false);
        final String slackUserId = "U0123456789";

        dmOnly.send(buildRequest(slackUserId));

        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/chat.postMessage")));
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/chat.postMessage"))
                .withRequestBody(containing("\"channel\":\"" + slackUserId + "\"")));
        then(messageStore).should().save(
                eq(INCIDENT_ID), eq(slackUserId), eq(TENANT_ID), eq(SLACK_TS));
        then(messageStore).should(never()).save(
                eq(INCIDENT_ID), eq(DEFAULT_CHANNEL), eq(TENANT_ID), eq(SLACK_TS));
    }

    @Test
    @DisplayName("broadcast disabled and no Slack user id: nothing is posted, and it fails loudly instead of reporting a delivery")
    void broadcastDisabledAndNoDmFailsLoudly() {
        final SlackNotificationChannel dmOnly = newChannel(false);

        assertThatThrownBy(() -> dmOnly.send(buildRequest("oncall@test.com")))
                .isInstanceOf(NotificationException.class);

        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/chat.postMessage")));
        then(messageStore).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("isSlackUserId is the predicate the router uses: only a non-blank id starting with U")
    void isSlackUserIdPredicate() {
        assertThat(SlackNotificationChannel.isSlackUserId("U0123456789")).isTrue();
        assertThat(SlackNotificationChannel.isSlackUserId("W0123456789")).isFalse();
        assertThat(SlackNotificationChannel.isSlackUserId("@handle")).isFalse();
        assertThat(SlackNotificationChannel.isSlackUserId("#incidents")).isFalse();
        assertThat(SlackNotificationChannel.isSlackUserId(" ")).isFalse();
        assertThat(SlackNotificationChannel.isSlackUserId(null)).isFalse();
    }

    @Test
    @DisplayName("the message has no Acknowledge button — a click from a tenant's own Slack App can't pass signature verification (backlog #0-35)")
    void messageHasNoAckButton() {
        channel.send(buildRequest("U0123456789"));

        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/chat.postMessage"))
                .withRequestBody(containing("acknowledge_incident")));
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/chat.postMessage"))
                .withRequestBody(containing("\"type\":\"actions\"")));
        wireMock.verify(2, postRequestedFor(urlPathEqualTo("/chat.postMessage"))
                .withRequestBody(containing("Acknowledge this incident in the Incident Platform app")));
    }

    @Test
    @DisplayName("posts with the tenant's own bot token, read from its workspace (backlog #0-21)")
    void postsWithTenantBotToken() {
        channel.send(buildRequest("U0123456789"));

        wireMock.verify(2, postRequestedFor(urlPathEqualTo("/chat.postMessage"))
                .withHeader("Authorization", equalTo("Bearer " + BOT_TOKEN)));
        then(slackWorkspaceClient).should().getWorkspace(TENANT_ID);
    }

    @Test
    @DisplayName("no workspace for the tenant: nothing posted, fails loudly (router bypassed)")
    void noWorkspaceFailsLoudly() {
        given(slackWorkspaceClient.getWorkspace(TENANT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> channel.send(buildRequest("U0123456789")))
                .isInstanceOf(NotificationException.class)
                .hasMessageContaining("No active Slack workspace");

        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/chat.postMessage")));
        then(messageStore).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("auth-service unavailable: NotificationException (recorded as a failed channel), cause kept, nothing posted")
    void lookupFailureBecomesNotificationException() {
        final SlackWorkspaceLookupUnavailableException outage =
                new SlackWorkspaceLookupUnavailableException("down", new RuntimeException());
        given(slackWorkspaceClient.getWorkspace(TENANT_ID)).willThrow(outage);

        assertThatThrownBy(() -> channel.send(buildRequest("U0123456789")))
                .isInstanceOf(NotificationException.class)
                .hasMessageContaining("auth-service unavailable")
                .hasCause(outage);

        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/chat.postMessage")));
    }

    private NotificationRequest buildRequest(String recipient) {
        return new NotificationRequest(
                INCIDENT_ID, TENANT_ID, "IncidentOpenedEvent",
                recipient, "[CRITICAL] High CPU", "message",
                Severity.CRITICAL, "High CPU");
    }
}