package com.incidentplatform.notification.slack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SlackSignatureVerifier")
class SlackSignatureVerifierTest {

    private SlackSignatureVerifier verifier;

    private static final String TEST_SIGNING_SECRET = "test-signing-secret-32chars-long";
    private static final String TEST_BODY = "payload=%7B%22type%22%3A%22block_actions%22%7D";

    @BeforeEach
    void setUp() {
        verifier = new SlackSignatureVerifier();
        ReflectionTestUtils.setField(verifier, "signingSecret", TEST_SIGNING_SECRET);
    }

    @Nested
    @DisplayName("valid signature")
    class ValidSignature {

        @Test
        @DisplayName("should verify correct HMAC-SHA256 signature")
        void shouldVerifyCorrectSignature() throws Exception {
            // given
            final String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            final String signature = computeValidSignature(timestamp, TEST_BODY);

            // when / then
            assertThat(verifier.verify(signature, timestamp, TEST_BODY)).isTrue();
        }

        @Test
        @DisplayName("should verify signature with empty body")
        void shouldVerifyEmptyBody() throws Exception {
            // given
            final String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            final String body = "";
            final String signature = computeValidSignature(timestamp, body);

            // when / then
            assertThat(verifier.verify(signature, timestamp, body)).isTrue();
        }
    }

    @Nested
    @DisplayName("invalid signature")
    class InvalidSignature {

        @Test
        @DisplayName("should reject incorrect signature")
        void shouldRejectIncorrectSignature() {
            // given
            final String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            final String wrongSignature = "v0=0000000000000000000000000000000000000000000000000000000000000000";

            // when / then
            assertThat(verifier.verify(wrongSignature, timestamp, TEST_BODY))
                    .isFalse();
        }

        @Test
        @DisplayName("should reject when signature is null")
        void shouldRejectNullSignature() {
            final String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            assertThat(verifier.verify(null, timestamp, TEST_BODY)).isFalse();
        }

        @Test
        @DisplayName("should reject when timestamp is null")
        void shouldRejectNullTimestamp() throws Exception {
            final String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            final String signature = computeValidSignature(timestamp, TEST_BODY);
            assertThat(verifier.verify(signature, null, TEST_BODY)).isFalse();
        }

        @Test
        @DisplayName("should reject when body is null")
        void shouldRejectNullBody() throws Exception {
            final String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            final String signature = computeValidSignature(timestamp, TEST_BODY);
            assertThat(verifier.verify(signature, timestamp, null)).isFalse();
        }
    }

    @Nested
    @DisplayName("replay attack protection")
    class ReplayAttackProtection {

        @Test
        @DisplayName("should reject timestamp older than 5 minutes")
        void shouldRejectOldTimestamp() throws Exception {
            // given
            final long oldTimestamp =
                    System.currentTimeMillis() / 1000 - (6 * 60);
            final String timestamp = String.valueOf(oldTimestamp);
            final String signature = computeValidSignature(timestamp, TEST_BODY);

            // when / then
            assertThat(verifier.verify(signature, timestamp, TEST_BODY))
                    .isFalse();
        }

        @Test
        @DisplayName("should accept timestamp exactly at 5 minute boundary")
        void shouldAcceptTimestampAtBoundary() throws Exception {
            // given
            final long recentTimestamp =
                    System.currentTimeMillis() / 1000 - (4 * 60 + 59);
            final String timestamp = String.valueOf(recentTimestamp);
            final String signature = computeValidSignature(timestamp, TEST_BODY);

            // when / then
            assertThat(verifier.verify(signature, timestamp, TEST_BODY))
                    .isTrue();
        }

        @Test
        @DisplayName("should reject invalid timestamp format")
        void shouldRejectInvalidTimestampFormat() throws Exception {
            final String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            final String signature = computeValidSignature(timestamp, TEST_BODY);
            assertThat(verifier.verify(signature, "not-a-number", TEST_BODY))
                    .isFalse();
        }
    }

    private String computeValidSignature(String timestamp,
                                         String body) throws Exception {
        final String baseString = "v0:" + timestamp + ":" + body;
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                TEST_SIGNING_SECRET.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        final byte[] hash = mac.doFinal(
                baseString.getBytes(StandardCharsets.UTF_8));

        final StringBuilder sb = new StringBuilder("v0=");
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}