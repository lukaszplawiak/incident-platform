package com.incidentplatform.auth.service;

import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import org.springframework.http.HttpStatus;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for secrets stored in the database that must be
 * read back in plaintext (unlike a password/API-key hash, which never is).
 *
 * <h2>Why AES-256-GCM</h2>
 * TOTP verification requires the original MFA secret (to generate the
 * expected code and compare); a Slack bot token must be handed back to
 * notification-service as-is to call the Slack API. BCrypt/Argon2 are
 * one-way — they cannot be used for either. AES-256-GCM provides:
 * <ul>
 *   <li><b>Confidentiality:</b> ciphertext is meaningless without the key</li>
 *   <li><b>Integrity:</b> GCM authentication tag detects tampering</li>
 *   <li><b>Semantic security:</b> random IV per encryption means identical
 *       secrets produce different ciphertexts</li>
 * </ul>
 *
 * <h2>One class, independently-keyed instances (backlog #0-21)</h2>
 * This class used to be a single {@code @Service} bound to one
 * {@code mfa.encryption-key}. It is now a plain, unmanaged helper — the key
 * is still supplied via the constructor, but {@link AesEncryptionConfig}
 * produces two separately-named {@code @Bean}s from it (one per
 * {@code mfa.encryption-key}, one per {@code slack.encryption-key}), so a
 * compromise of one key does not expose data encrypted under the other.
 * Consumers inject the one they need via {@code @Qualifier}.
 *
 * <h2>Separation of concerns</h2>
 * The encryption key lives in the environment — never in the database or
 * source code. Even a full DB dump is useless without the key. Key rotation
 * requires re-encrypting all secrets under that key (a planned operational
 * procedure, not an emergency).
 *
 * <h2>Output format</h2>
 * {@code base64(iv) + ":" + base64(ciphertext+tag)}
 * IV is 12 bytes (96 bits) — NIST recommended for GCM.
 * GCM tag is 128 bits (16 bytes) — appended to ciphertext by JCE.
 *
 * <h2>Key format</h2>
 * Each key must be a 32-byte base64-encoded value.
 * Generate with: {@code openssl rand -base64 32}
 */
public class AesEncryptionService {

    private static final String ALGORITHM       = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LENGTH   = 12;  // bytes — NIST recommended
    private static final int    GCM_TAG_BITS    = 128; // bits — maximum strength

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesEncryptionService(String base64Key) {
        final byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "Encryption key must be a 32-byte base64-encoded key. " +
                            "Generate with: openssl rand -base64 32");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Encrypts a plain text value with AES-256-GCM.
     *
     * @param plaintext the value to encrypt (e.g. base32 TOTP secret)
     * @return {@code base64(iv):base64(ciphertext+tag)} — safe to store in DB
     */
    public String encrypt(String plaintext) {
        try {
            final byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            final Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey,
                    new GCMParameterSpec(GCM_TAG_BITS, iv));

            final byte[] ciphertext = cipher.doFinal(
                    plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(iv)
                    + ":"
                    + Base64.getEncoder().encodeToString(ciphertext);

        } catch (Exception e) {
            throw new RuntimeException("AES-GCM encryption failed", e);
        }
    }

    /**
     * Decrypts a value previously encrypted by {@link #encrypt}.
     *
     * @param encrypted {@code base64(iv):base64(ciphertext+tag)}
     * @return original plaintext
     * @throws BusinessException if the ciphertext has been tampered with
     */
    public String decrypt(String encrypted) {
        try {
            final String[] parts = encrypted.split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        "Invalid encrypted format — expected iv:ciphertext");
            }

            final byte[] iv         = Base64.getDecoder().decode(parts[0]);
            final byte[] ciphertext = Base64.getDecoder().decode(parts[1]);

            final Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey,
                    new GCMParameterSpec(GCM_TAG_BITS, iv));

            final byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);

        } catch (javax.crypto.AEADBadTagException e) {
            // GCM tag mismatch — data has been tampered with
            throw new BusinessException(
                    ErrorCodes.UNAUTHORIZED,
                    "Encrypted value integrity check failed — possible tampering",
                    HttpStatus.UNAUTHORIZED);
        } catch (Exception e) {
            throw new RuntimeException("AES-GCM decryption failed", e);
        }
    }
}