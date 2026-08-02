package com.incidentplatform.notification.api;

import com.incidentplatform.notification.config.SecurityConfig;
import com.incidentplatform.notification.slack.SlackActionService;
import com.incidentplatform.notification.slack.SlackSignatureVerifier;
import com.incidentplatform.shared.security.JwtAuthFilter;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.ServiceTokenProvider;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security tests for {@link SlackWebhookController} — previously with no
 * test coverage of any kind.
 *
 * <h2>A fundamentally different access model from every other controller
 * covered so far</h2>
 * No {@code @PreAuthorize}, no JWT at all — {@code /api/v1/slack/actions}
 * is explicitly {@code permitAll()} in {@code SecurityConfig} (see its
 * class Javadoc). Slack can't obtain a platform JWT or API key, so this
 * endpoint authenticates itself manually via HMAC-SHA256 signature
 * verification ({@link SlackSignatureVerifier}) over the raw request
 * body — the same pattern used industry-wide for third-party webhooks
 * (Stripe, GitHub, Slack all do this). "Security testing" here means
 * proving the signature check actually gates the endpoint, not testing
 * role-based access — there are no roles involved.
 *
 * <p>No {@code principal(...)} helper needed here, unlike every other
 * *SecurityTest in this codebase — there's no
 * {@code @AuthenticationPrincipal} on this controller at all.
 */
@WebMvcTest(SlackWebhookController.class)
@ContextConfiguration(classes = SlackWebhookControllerSecurityTest.TestApplication.class)
@Import({SecurityConfig.class, UnauthorizedEntryPoint.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.access-token-ttl=PT15M",
        "jwt.service-token-ttl=PT1H",
        "spring.application.name=notification-service"
})
@DisplayName("SlackWebhookController — security")
class SlackWebhookControllerSecurityTest {

    @SpringBootApplication(scanBasePackages = {
            "com.incidentplatform.notification.api",
            "com.incidentplatform.notification.config",
            "com.incidentplatform.shared.security",
            "com.incidentplatform.shared.exception"
    })
    static class TestApplication {

        @org.springframework.context.annotation.Bean
        public JwtAuthFilter jwtAuthFilter(JwtUtils jwtUtils) {
            return new JwtAuthFilter(jwtUtils);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SlackSignatureVerifier signatureVerifier;

    @MockitoBean
    private SlackActionService slackActionService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private ServiceTokenProvider serviceTokenProvider;

    private static final String VALID_PAYLOAD_BODY =
            "payload=%7B%22type%22%3A%22block_actions%22%7D";

    @Nested
    @DisplayName("signature verification")
    class SignatureVerification {

        @Test
        @DisplayName("200 without any Authorization header — reachable without a JWT, by design")
        void reachableWithoutJwt() throws Exception {
            given(signatureVerifier.verify(anyString(), anyString(), anyString()))
                    .willReturn(true);

            // Deliberately no .header("Authorization", ...) anywhere here —
            // proving permitAll() actually works, not just that we forgot
            // to assert 401.
            mockMvc.perform(post("/api/v1/slack/actions")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .header("X-Slack-Signature", "v0=validsignature")
                            .header("X-Slack-Request-Timestamp", "1700000000")
                            .content(VALID_PAYLOAD_BODY))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("401 when the signature fails verification")
        void returns401OnInvalidSignature() throws Exception {
            given(signatureVerifier.verify(anyString(), anyString(), anyString()))
                    .willReturn(false);

            mockMvc.perform(post("/api/v1/slack/actions")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .header("X-Slack-Signature", "v0=forgedsignature")
                            .header("X-Slack-Request-Timestamp", "1700000000")
                            .content(VALID_PAYLOAD_BODY))
                    .andExpect(status().isUnauthorized());

            then(slackActionService).should(never()).processAction(any());
        }

        @Test
        @DisplayName("401 when the X-Slack-Signature header is missing entirely")
        void returns401WhenSignatureHeaderMissing() throws Exception {
            // No signature header at all -> verify() receives null and (per
            // its own contract) must treat that as invalid, same as a
            // forged one. Mocked here to return false for a null arg to
            // pin that expectation at the controller-test level; the real
            // null-handling behavior belongs to SlackSignatureVerifierTest.
            given(signatureVerifier.verify(any(), anyString(), anyString()))
                    .willReturn(false);

            mockMvc.perform(post("/api/v1/slack/actions")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .header("X-Slack-Request-Timestamp", "1700000000")
                            .content(VALID_PAYLOAD_BODY))
                    .andExpect(status().isUnauthorized());

            then(slackActionService).should(never()).processAction(any());
        }

        @Test
        @DisplayName("does not process the action when the signature is invalid")
        void doesNotProcessOnInvalidSignature() throws Exception {
            given(signatureVerifier.verify(anyString(), anyString(), anyString()))
                    .willReturn(false);

            mockMvc.perform(post("/api/v1/slack/actions")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header("X-Slack-Signature", "v0=forgedsignature")
                    .header("X-Slack-Request-Timestamp", "1700000000")
                    .content(VALID_PAYLOAD_BODY));

            then(slackActionService).should(never()).processAction(any());
        }
    }

    @Nested
    @DisplayName("payload handling (signature already valid)")
    class PayloadHandling {

        @Test
        @DisplayName("200 and processes the action for a valid signature and payload")
        void processesValidPayload() throws Exception {
            given(signatureVerifier.verify(anyString(), anyString(), anyString()))
                    .willReturn(true);

            mockMvc.perform(post("/api/v1/slack/actions")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .header("X-Slack-Signature", "v0=validsignature")
                            .header("X-Slack-Request-Timestamp", "1700000000")
                            .content(VALID_PAYLOAD_BODY))
                    .andExpect(status().isOk());

            then(slackActionService).should().processAction(anyString());
        }

        @Test
        @DisplayName("400 when the signature is valid but the payload field is missing")
        void returns400WhenPayloadFieldMissing() throws Exception {
            given(signatureVerifier.verify(anyString(), anyString(), anyString()))
                    .willReturn(true);

            mockMvc.perform(post("/api/v1/slack/actions")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .header("X-Slack-Signature", "v0=validsignature")
                            .header("X-Slack-Request-Timestamp", "1700000000")
                            .content("some_other_field=value"))
                    .andExpect(status().isBadRequest());

            then(slackActionService).should(never()).processAction(any());
        }
    }
}