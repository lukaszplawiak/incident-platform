package com.incidentplatform.auth.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates and hashes API keys.
 *
 * <h2>Key format</h2>
 * {@code ipl_<random32>} — e.g. {@code ipl_xY3kP9qR2vN8mZ7wA1bC4dE6fG0hJ5iK}
 * <ul>
 *   <li>{@code ipl_} — platform prefix, distinguishes from other secret types</li>
 *   <li>{@code <random32>} — 24 bytes of SecureRandom → base64url, ~143 bits
 *       entropy, exactly 32 characters (24 bytes × 8 bits ÷ 6 bits-per-char,
 *       no padding needed)</li>
 * </ul>
 *
 * <h2>Fixed (backlog #65): corrected misleading key-format documentation</h2>
 * Previously documented as {@code ipl_<prefix8>.<random32>} — the dot
 * implied a two-part, delimited structure with a separate 8-character
 * prefix segment. The generated key has no dot at all: it's one
 * continuous string, {@code ipl_} directly followed by the full
 * 32-character random part. {@link #extractPrefix} does not split on any
 * delimiter — it takes the first 8 characters of that same continuous
 * random part via {@code substring(0, 8)}. Someone reading only the old
 * Javadoc could reasonably, but incorrectly, assume the raw key could be
 * split on {@code "."} to recover the prefix.
 *
 * <h2>Why SHA-256 not Argon2</h2>
 * API keys have 143 bits of entropy — brute-forcing a leaked SHA-256 hash
 * is computationally infeasible (2^143 attempts). Argon2's memory-hard cost
 * would add 100ms+ latency to every API request. SHA-256 is the industry
 * standard for high-entropy API key hashing (GitHub, Stripe, Twilio).
 *
 * <h2>Why not store prefix separately from hash</h2>
 * The prefix is derived from the raw key before hashing.
 * An attacker with the DB cannot derive the raw key from prefix + hash.
 * The prefix only lets the user identify which key they're looking at in the UI.
 */
@Component
public class ApiKeyHasher {

    private static final String KEY_PREFIX = "ipl_";
    private static final int    RANDOM_BYTES = 24; // 192 bits → 32 base64url chars

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Generates a new raw API key.
     *
     * @return {@code ipl_<random32>} — see class Javadoc for the exact format
     */
    public String generateRawKey() {
        final byte[] randomBytes = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(randomBytes);
        final String randomPart = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);
        return KEY_PREFIX + randomPart;
    }

    /**
     * Extracts the 8-character prefix for UI display.
     *
     * <p>The prefix is the first 8 characters after {@code ipl_}.
     * Safe to store — does not reveal the secret.
     */
    public String extractPrefix(String rawKey) {
        // rawKey = "ipl_<32chars...>"
        // prefix = first 8 chars after "ipl_"
        final String withoutIpl = rawKey.substring(KEY_PREFIX.length());
        return withoutIpl.substring(0, Math.min(8, withoutIpl.length()));
    }

    /**
     * Computes SHA-256 hash of a raw key for storage.
     *
     * @return lowercase hex string of the SHA-256 digest
     */
    public String hash(String rawKey) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hashBytes = digest.digest(
                    rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by JVM spec — never thrown
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Verifies a raw key against a stored hash using constant-time comparison.
     *
     * <p>Constant-time via {@link MessageDigest#isEqual} — prevents timing
     * attacks where an attacker could determine correct prefix bits by
     * measuring response time differences.
     */
    public boolean verify(String rawKey, String storedHash) {
        final String computedHash = hash(rawKey);
        return MessageDigest.isEqual(
                computedHash.getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns true if the string looks like an API key (starts with prefix).
     * Used by {@code ApiKeyAuthFilter} to quickly skip non-key tokens.
     */
    public boolean isApiKey(String token) {
        return token != null && token.startsWith(KEY_PREFIX);
    }
}