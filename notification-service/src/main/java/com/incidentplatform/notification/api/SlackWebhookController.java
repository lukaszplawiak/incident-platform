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

// Odbiera interaktywne akcje od Slacka (kliknięcia przycisków).
//
// Wymagania Slacka:
// 1. Odpowiedź HTTP 200 w ciągu 3 sekund — inaczej Slack ponowi webhook
// 2. Weryfikacja podpisu HMAC-SHA256 — żeby odrzucić fałszywe webhooki
//
// Flow:
// Engineer klika [✅ Acknowledge] w Slacku
//   → Slack wysyła POST /api/v1/slack/actions
//   → Weryfikujemy podpis (HMAC-SHA256)
//   → Odpowiadamy 200 natychmiast
//   → @Async SlackActionService przetwarza w tle:
//       → wywołuje incident-service (PATCH /status)
//       → aktualizuje wiadomość Slack (chat.update)
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

    // Slack wysyła payload jako application/x-www-form-urlencoded
    // z polem "payload" zawierającym JSON
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

        // Krok 1 — weryfikacja podpisu HMAC-SHA256
        // Odrzucamy natychmiast jeśli podpis jest niepoprawny
        if (!signatureVerifier.verify(slackSignature, slackTimestamp, rawBody)) {
            log.warn("Slack webhook rejected — invalid signature");
            return ResponseEntity.status(401).build();
        }

        // Krok 2 — wyciągamy JSON payload z form-urlencoded body
        // Slack wysyła: payload=%7B%22type%22%3A%22block_actions%22...%7D
        final String jsonPayload = extractPayload(rawBody);
        if (jsonPayload == null) {
            log.warn("Slack webhook received without payload field");
            return ResponseEntity.badRequest().build();
        }

        // Krok 3 — odpowiadamy Slackowi 200 NATYCHMIAST
        // Przetwarzanie jest @Async — nie blokujemy wątku HTTP
        slackActionService.processAction(jsonPayload);

        log.debug("Slack webhook accepted — processing asynchronously");
        return ResponseEntity.ok().build();
    }

    // Wyciąga wartość pola "payload" z application/x-www-form-urlencoded body
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