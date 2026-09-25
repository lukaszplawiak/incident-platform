package com.incidentplatform.shared.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The one hash function for API keys: lowercase hex SHA-256 of the raw key.
 *
 * <p>Moved to {@code shared} (backlog #0-16) because two services now compute
 * it: auth-service stores and looks up keys by it, and ingestion-service sends
 * it to auth-service's introspection endpoint instead of the raw key, so the
 * raw key never leaves the service that received it. Both must produce the
 * same string for the same key.
 *
 * <p>A fast, unsalted hash is correct here: keys carry 192 random bits, far
 * above the 112-bit threshold below which NIST SP 800-63B asks for a salted,
 * slow hash, and a salt would rule out the indexed lookup by hash.
 */
public final class ApiKeyHashing {

    private ApiKeyHashing() {
    }

    /** @return lowercase hex SHA-256 digest of {@code rawKey} (64 characters) */
    public static String sha256Hex(String rawKey) {
        if (rawKey == null) {
            throw new IllegalArgumentException("rawKey must not be null");
        }
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(rawKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Every JVM must provide SHA-256 (java.security.MessageDigest spec).
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
