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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

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
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
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
                slackWorkspaceClient,
                meterRegistry);
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
                .isInstanceOfSatisfying(NotificationException.class, e -> assertThat(e.reason())
                        .isEqualTo(NotificationFailureReason.SLACK_WORKSPACE_UNAVAILABLE))
                .hasCause(outage);

        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/chat.postMessage")));
    }

    /**
     * Backlog #0-93: Slack answers most failures with HTTP 200 and
     * {@code "ok":false}; that used to be read as a delivery (recorded SENT).
     */
    private void slackAnswers(String path, String body) {
        wireMock.stubFor(post(urlPathEqualTo(path))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    @Test
    @DisplayName("ok:false is a failure with Slack's code, not a delivery; nothing stored, Slack's text not kept "
            + "(backlog #0-93)")
    void okFalseIsRejected() {
        slackAnswers("/chat.postMessage",
                "{\"ok\":false,\"error\":\"not_in_channel\",\"warning\":\"see https://internal.example\"}");

        assertThatThrownBy(() -> channel.send(buildRequest("U0123456789")))
                .isInstanceOfSatisfying(NotificationException.class, e -> {
                    assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_REJECTED);
                    assertThat(e.detail()).isEqualTo("not_in_channel");
                })
                .hasMessage("SLACK_REJECTED (not_in_channel): Slack refused the message");
        then(messageStore).should(never()).save(any(), any(), any(), any());
    }

    @Test
    @DisplayName("ok:false ratelimited is SLACK_RATE_LIMITED, a transient code SLACK_UNAVAILABLE")
    void okFalseTransientCodes() {
        slackAnswers("/chat.postMessage", "{\"ok\":false,\"error\":\"ratelimited\"}");
        assertThatThrownBy(() -> channel.send(buildRequest("U0123456789")))
                .isInstanceOfSatisfying(NotificationException.class,
                        e -> assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_RATE_LIMITED));

        slackAnswers("/chat.postMessage", "{\"ok\":false,\"error\":\"internal_error\"}");
        assertThatThrownBy(() -> channel.send(buildRequest("U0123456789")))
                .isInstanceOfSatisfying(NotificationException.class, e -> {
                    assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_UNAVAILABLE);
                    assertThat(e.reason().permanent()).isFalse();
                });
    }

    @Test
    @DisplayName("an answer that is not Slack's JSON, or has no ok field, is SLACK_UNAVAILABLE (unreadable_response)")
    void unreadableAnswer() {
        for (final String body : new String[] {"<html>gateway</html>", "{\"ts\":\"1.2\"}"}) {
            slackAnswers("/chat.postMessage", body);
            assertThatThrownBy(() -> channel.send(buildRequest("U0123456789")))
                    .as(body)
                    .isInstanceOfSatisfying(NotificationException.class, e -> {
                        assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_UNAVAILABLE);
                        assertThat(e.detail()).isEqualTo("unreadable_response");
                    })
                    .hasMessageNotContaining("gateway");
        }
    }

    @Test
    @DisplayName("a Slack code that is not a plain code is dropped, the reason kept")
    void oddCodeDropped() {
        slackAnswers("/chat.postMessage", "{\"ok\":false,\"error\":\"Bad Thing\\nforged line\"}");

        final ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SlackNotificationChannel.class);
        final ch.qos.logback.classic.Level level = logger.getLevel();
        final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            assertThatThrownBy(() -> channel.send(buildRequest("U0123456789")))
                    .isInstanceOfSatisfying(NotificationException.class, e -> {
                        assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_REJECTED);
                        assertThat(e.detail()).isNull();
                    })
                    .hasMessage("SLACK_REJECTED: Slack refused the message");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(level);
        }
        // Review: the dropped code leaves a trace at DEBUG, on one line.
        assertThat(appender.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("error=Bad Thing?forged line").doesNotContain("\n"));
    }

    @Test
    @DisplayName("the update after an ACK checks ok too: ok:false is a failure, so its ts is kept for a retry")
    void updateOkFalseIsFailure() {
        slackAnswers("/chat.update", "{\"ok\":false,\"error\":\"message_not_found\"}");
        final NotificationRequest original = buildRequest("U0123456789");

        assertThatThrownBy(() -> channel.updateMessageAfterAck(DEFAULT_CHANNEL, SLACK_TS, "Jane", original, BOT_TOKEN))
                .isInstanceOfSatisfying(NotificationException.class, e -> {
                    assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_REJECTED);
                    assertThat(e.detail()).isEqualTo("message_not_found");
                });

        slackAnswers("/chat.update", "{\"ok\":true}");
        channel.updateMessageAfterAck(DEFAULT_CHANNEL, SLACK_TS, "Jane", original, BOT_TOKEN);
    }

    @Test
    @DisplayName("ok:false without an error code is SLACK_REJECTED, not an unexpected error (second review)")
    void okFalseWithoutCode() {
        slackAnswers("/chat.postMessage", "{\"ok\":false}");

        assertThatThrownBy(() -> channel.send(buildRequest("U0123456789")))
                .isInstanceOfSatisfying(NotificationException.class, e -> {
                    assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_REJECTED);
                    assertThat(e.detail()).isNull();
                });
    }

    @Test
    @DisplayName("a token Slack no longer accepts is SLACK_AUTH_FAILED, logged at ERROR (second review)")
    void revokedToken() {
        for (final String code : new String[] {"invalid_auth", "token_revoked", "account_inactive"}) {
            slackAnswers("/chat.postMessage", "{\"ok\":false,\"error\":\"" + code + "\"}");
            assertThatThrownBy(() -> channel.send(buildRequest("U0123456789")))
                    .as(code)
                    .isInstanceOfSatisfying(NotificationException.class, e -> {
                        assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_AUTH_FAILED);
                        assertThat(e.reason().permanent()).isFalse();
                        assertThat(e.detail()).isEqualTo(code);
                    });
        }
    }

    /**
     * Second review: send() calls postIncidentMessage on itself, past the retry
     * proxy and its fallback, so an HTTP error must be classified there too, not
     * reach the caller raw (recorded as an unexpected error).
     */
    @Test
    @DisplayName("an HTTP error on the send path is classified by its status, Slack's body not kept")
    void httpErrorOnSendPath() {
        wireMock.stubFor(post(urlPathEqualTo("/chat.postMessage"))
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"secret body\"}")));

        assertThatThrownBy(() -> channel.send(buildRequest("U0123456789")))
                .isInstanceOfSatisfying(NotificationException.class, e -> {
                    assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_AUTH_FAILED);
                    assertThat(e.detail()).isEqualTo("http_401");
                })
                .hasMessageNotContaining("secret");
    }

    /**
     * Second review: before #0-93 a refused broadcast passed as a success, so
     * the DM always went; now that it fails, it must not stop the DM.
     */
    @Test
    @DisplayName("a refused broadcast still lets the on-call DM go, and the send counts as delivered; the "
            + "broadcast's failure is counted under SLACK_BROADCAST and logged by its permanence")
    void refusedBroadcastStillDms() {
        final ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SlackNotificationChannel.class);
        final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            refuseBroadcast("is_archived");
            channel.send(buildRequest("U0123456789"));
            refuseBroadcast("internal_error");
            channel.send(buildRequest("U0123456789"));
        } finally {
            logger.detachAppender(appender);
        }

        final var broadcastLines = appender.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("Slack broadcast failed")).toList();
        assertThat(broadcastLines).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getLevel)
                .containsExactly(ch.qos.logback.classic.Level.WARN, ch.qos.logback.classic.Level.ERROR);
        assertThat(meterRegistry.counter("notification.channel.failed", "channel", "SLACK_BROADCAST",
                "reason", "SLACK_REJECTED").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("notification.channel.failed", "channel", "SLACK_BROADCAST",
                "reason", "SLACK_UNAVAILABLE").count()).isEqualTo(1.0);

        wireMock.verify(postRequestedFor(urlPathEqualTo("/chat.postMessage"))
                .withRequestBody(containing("\"channel\":\"U0123456789\"")));
        then(messageStore).should(times(2))
                .save(INCIDENT_ID, "U0123456789", TENANT_ID, SLACK_TS);
        then(messageStore).should(never()).save(eq(INCIDENT_ID), eq(DEFAULT_CHANNEL), any(), any());
    }

    private void refuseBroadcast(String slackError) {
        wireMock.stubFor(post(urlPathEqualTo("/chat.postMessage"))
                .withRequestBody(containing("\"channel\":\"" + DEFAULT_CHANNEL + "\""))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":false,\"error\":\"" + slackError + "\"}")));
    }

    @Test
    @DisplayName("with no DM to send, a refused broadcast is the channel's failure")
    void refusedBroadcastWithoutDmFails() {
        slackAnswers("/chat.postMessage", "{\"ok\":false,\"error\":\"is_archived\"}");

        assertThatThrownBy(() -> channel.send(buildRequest("#not-a-user")))
                .isInstanceOfSatisfying(NotificationException.class, e -> {
                    assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_REJECTED);
                    assertThat(e.detail()).isEqualTo("is_archived");
                });
    }

    private NotificationRequest buildRequest(String recipient) {
        return new NotificationRequest(
                INCIDENT_ID, TENANT_ID, "IncidentOpenedEvent",
                recipient, "[CRITICAL] High CPU", "message",
                Severity.CRITICAL, "High CPU");
    }
}