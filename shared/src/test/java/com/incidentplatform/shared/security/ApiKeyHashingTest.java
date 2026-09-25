package com.incidentplatform.shared.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ApiKeyHashing")
class ApiKeyHashingTest {

    @Test
    @DisplayName("is lowercase hex SHA-256 — the value stored in api_keys.key_hash")
    void knownVector() {
        // SHA-256("abc"), FIPS 180-2 test vector
        assertThat(ApiKeyHashing.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("is deterministic and 64 characters long")
    void deterministic() {
        final String key = "ipl_" + "x".repeat(32);
        assertThat(ApiKeyHashing.sha256Hex(key))
                .isEqualTo(ApiKeyHashing.sha256Hex(key))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("rejects null")
    void rejectsNull() {
        assertThatThrownBy(() -> ApiKeyHashing.sha256Hex(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
