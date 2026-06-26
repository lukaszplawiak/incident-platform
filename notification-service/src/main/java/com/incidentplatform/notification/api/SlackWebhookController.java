package com.incidentplatform.notification.api;

import com.incidentplatform.notification.slack.SlackActionService;
import com.incidentplatform.notification.slack.SlackSignatureVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Receives interactive actions from Slack (button clicks).
//
// Slack requirements:
// 1. HTTP 200 response within 3 seconds — otherwise Slack retries the webhook
// 2. HMAC-SHA256 signature verification — to reject forged webhooks
//
// Flow:
// Engineer clicks [✅ Acknowledge] in Slack
//   → Slack sends POST /api/v1/slack/actions
//   → Signature verified (HMAC-SHA256)
//   → Respond 200 immediately
//   → @Async SlackActionService processes in background:
//       → calls incident-service (PATCH /status)
//       → updates Slack message (chat.update)
@RestController
@RequestMapping("/api/v1/slack")
public class SlackWebhookController {

    private static final Logger log =
            LoggerFactory.getLogger(SlackWebhookController.class);

    private final SlackSignatureVerifier signatureVerifier;
    private final SlackActionService slackActionService;

    public SlackWebhookController(SlackSignatureVerifier signatureVerifier,
                                  SlackActionService slackActionService) {
        this.signatureVerifier = signatureVerifier;
        this.slackActionService = slackActionService;
    }

    // Slack sends the payload as application/x-www-form-urlencoded
    // with a "payload" field containing JSON.
    @PostMapping(
            value = "/actions",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE
    )
    public ResponseEntity<Void> handleAction(
            @RequestHeader(value = "X-Slack-Signature",
                    required = false) String slackSignature,
            @RequestHeader(value = "X-Slack-Request-Timestamp",
                    required = false) String slackTimestamp,
            @RequestBody String rawBody) {

        log.debug("Slack webhook received: timestamp={}", slackTimestamp);

        // Step 1 — HMAC-SHA256 signature verification.
        // Reject immediately if the signature is invalid.
        if (!signatureVerifier.verify(slackSignature, slackTimestamp, rawBody)) {
            log.warn("Slack webhook rejected — invalid signature");
            return ResponseEntity.status(401).build();
        }

        // Step 2 — extract the JSON payload from the form-urlencoded body.
        // Slack sends: payload=%7B%22type%22%3A%22block_actions%22...%7D
        final String jsonPayload = extractPayload(rawBody);
        if (jsonPayload == null) {
            log.warn("Slack webhook received without payload field");
            return ResponseEntity.badRequest().build();
        }

        // Step 3 — respond to Slack with 200 IMMEDIATELY.
        // Processing is @Async — the HTTP thread is not blocked.
        slackActionService.processAction(jsonPayload);

        log.debug("Slack webhook accepted — processing asynchronously");
        return ResponseEntity.ok().build();
    }

    private String extractPayload(String rawBody) {
        if (rawBody == null) return null;

        for (final String part : rawBody.split("&")) {
            if (part.startsWith("payload=")) {
                try {
                    return java.net.URLDecoder.decode(
                            part.substring("payload=".length()),
                            java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    log.warn("Failed to URL-decode Slack payload: {}",
                            e.getMessage());
                    return null;
                }
            }
        }
        return null;
    }
}