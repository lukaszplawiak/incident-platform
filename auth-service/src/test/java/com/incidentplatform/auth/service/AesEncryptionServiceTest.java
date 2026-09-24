package com.incidentplatform.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the plain (unmanaged) {@link AesEncryptionService}.
 *
 * <p>Since backlog #0-21, {@link AesEncryptionConfig} produces two
 * independently-keyed beans from this class ({@code mfaEncryptionService},
 * {@code slackEncryptionService}) rather than one {@code @Service} — these
 * tests exercise the class directly with two different keys to confirm
 * that independence actually holds: a value encrypted under one key is not
 * readable under the other.
 */
@DisplayName("AesEncryptionService")
class AesEncryptionServiceTest {

    private static final String MFA_KEY = "dGVzdC1rZXktMzItYnl0ZXMtZm9yLWRldi1vbmx5ISE=";
    private static final String SLACK_KEY = "c2xhY2sta2V5LTMyLWJ5dGVzLWZvci1kZXYtb25seSE=";

    @Test
    @DisplayName("round-trips a value under its own key")
    void encryptDecrypt_roundTrips() {
        final AesEncryptionService service = new AesEncryptionService(MFA_KEY);

        final String encrypted = service.encrypt("a-totp-secret");

        assertThat(service.decrypt(encrypted)).isEqualTo("a-totp-secret");
    }

    @Test
    @DisplayName("two independently-keyed instances produce different ciphertext for the same plaintext")
    void differentKeys_produceDifferentCiphertext() {
        final AesEncryptionService mfa = new AesEncryptionService(MFA_KEY);
        final AesEncryptionService slack = new AesEncryptionService(SLACK_KEY);

        assertThat(mfa.encrypt("xoxb-some-token"))
                .isNotEqualTo(slack.encrypt("xoxb-some-token"));
    }

    @Test
    @DisplayName("a value encrypted under one key cannot be decrypted under the other — " +
            "confirms a compromise of one key does not expose data under the other (backlog #0-21)")
    void differentKeys_cannotCrossDecrypt() {
        final AesEncryptionService mfa = new AesEncryptionService(MFA_KEY);
        final AesEncryptionService slack = new AesEncryptionService(SLACK_KEY);

        final String encryptedWithMfaKey = mfa.encrypt("xoxb-some-token");

        assertThatThrownBy(() -> slack.decrypt(encryptedWithMfaKey))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("rejects a key that isn't 32 bytes once base64-decoded")
    void rejectsWrongLengthKey() {
        final String tooShort = Base64.getEncoder().encodeToString("too-short".getBytes());

        assertThatThrownBy(() -> new AesEncryptionService(tooShort))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32-byte");
    }
}
