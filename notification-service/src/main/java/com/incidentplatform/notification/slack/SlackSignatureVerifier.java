package com.incidentplatform.notification.slack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.incidentplatform.notification.config.NotificationChannelProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
public class SlackSignatureVerifier {

    private static final Logger log =
            LoggerFactory.getLogger(SlackSignatureVerifier.class);

    private static final String SLACK_VERSION = "v0";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final long MAX_TIMESTAMP_DIFF_SECONDS = 5 * 60;

    private final String signingSecret;

    public SlackSignatureVerifier(
            NotificationChannelProperties properties) {
        this.signingSecret = properties.channels().slack().signingSecret();
    }

    public boolean verify(String slackSignature,
                          String slackTimestamp,
                          String requestBody) {
        if (slackSignature == null || slackTimestamp == null
                || requestBody == null) {
            log.warn("Slack webhook verification failed — missing headers");
            return false;
        }

        try {
            final long timestamp = Long.parseLong(slackTimestamp);
            final long now = System.currentTimeMillis() / 1000;

            if (Math.abs(now - timestamp) > MAX_TIMESTAMP_DIFF_SECONDS) {
                log.warn("Slack webhook rejected — timestamp too old: " +
                                "timestamp={}, now={}, diff={}s",
                        timestamp, now, Math.abs(now - timestamp));
                return false;
            }
        } catch (NumberFormatException e) {
            log.warn("Slack webhook verification failed — invalid timestamp: {}",
                    slackTimestamp);
            return false;
        }

        final String baseString = SLACK_VERSION + ":" +
                slackTimestamp + ":" + requestBody;

        final String expectedSignature = computeHmac(baseString);
        if (expectedSignature == null) {
            return false;
        }

        final String expected = SLACK_VERSION + "=" + expectedSignature;
        final boolean valid = constantTimeEquals(expected, slackSignature);

        if (!valid) {
            log.warn("Slack webhook signature mismatch — possible forgery attempt");
        }

        return valid;
    }

    private String computeHmac(String data) {
        try {
            final Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            final SecretKeySpec keySpec = new SecretKeySpec(
                    signingSecret.getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM);
            mac.init(keySpec);
            final byte[] hash = mac.doFinal(
                    data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Failed to compute HMAC-SHA256: {}", e.getMessage());
            return null;
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        // MessageDigest.isEqual instead of a hand-rolled loop — same
        // reasoning and same JDK method already used by TotpService
        // (auth-service) for the equivalent concern (comparing a
        // user-submitted code against an expected value without leaking
        // timing information). Previously this method returned early on a
        // length mismatch before ever entering the XOR loop — a genuine,
        // if low-risk, timing side-channel (Slack's signature format has a
        // fixed, publicly documented length, so an attacker gains little
        // from this in practice, but there's no reason to keep a
        // partially-correct hand-rolled version when the JDK's own
        // constant-time comparison — correct regardless of length — is
        // one call away).
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private String bytesToHex(byte[] bytes) {
        final StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}