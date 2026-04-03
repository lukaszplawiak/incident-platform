package com.incidentplatform.shared.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TenantContext")
class TenantContextTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("should set and get tenantId")
    void shouldSetAndGetTenantId() {
        // when
        TenantContext.set("acme-corp");

        // then
        assertThat(TenantContext.get()).isEqualTo("acme-corp");
    }

    @Test
    @DisplayName("should throw IllegalStateException when context not set")
    void shouldThrowWhenContextNotSet() {
        assertThatThrownBy(() -> TenantContext.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TenantContext is not set");
    }

    @Test
    @DisplayName("should return null from getOrNull when context not set")
    void shouldReturnNullWhenContextNotSet() {
        // then
        assertThat(TenantContext.getOrNull()).isNull();
    }

    @Test
    @DisplayName("should return tenantId from getOrNull when context is set")
    void shouldReturnTenantIdFromGetOrNull() {
        // when
        TenantContext.set("acme-corp");

        // then
        assertThat(TenantContext.getOrNull()).isEqualTo("acme-corp");
    }

    @Test
    @DisplayName("should clear tenantId")
    void shouldClearTenantId() {
        // given
        TenantContext.set("acme-corp");
        assertThat(TenantContext.isSet()).isTrue();

        // when
        TenantContext.clear();

        // then
        assertThat(TenantContext.isSet()).isFalse();
        assertThat(TenantContext.getOrNull()).isNull();
    }

    @Test
    @DisplayName("should return false from isSet when context not set")
    void shouldReturnFalseFromIsSetWhenNotSet() {
        // then
        assertThat(TenantContext.isSet()).isFalse();
    }

    @Test
    @DisplayName("should return true from isSet when context is set")
    void shouldReturnTrueFromIsSetWhenSet() {
        // when
        TenantContext.set("acme-corp");

        // then
        assertThat(TenantContext.isSet()).isTrue();
    }

    @Test
    @DisplayName("should ignore null tenantId silently")
    void shouldIgnoreNullTenantId() {
        // when
        TenantContext.set(null);

        // then
        assertThat(TenantContext.isSet()).isFalse();
    }

    @Test
    @DisplayName("should ignore blank tenantId silently")
    void shouldIgnoreBlankTenantId() {
        // when
        TenantContext.set("   ");

        // then
        assertThat(TenantContext.isSet()).isFalse();
    }

    @Test
    @DisplayName("should overwrite previous tenantId")
    void shouldOverwritePreviousTenantId() {
        // given
        TenantContext.set("acme-corp");

        // when
        TenantContext.set("beta-corp");

        // then
        assertThat(TenantContext.get()).isEqualTo("beta-corp");
    }

    @Test
    @DisplayName("should be safe to call clear when context not set")
    void shouldBeSafeToClearWhenNotSet() {
        // when/then
        TenantContext.clear();
        assertThat(TenantContext.isSet()).isFalse();
    }

    @Test
    @DisplayName("should isolate context between threads")
    void shouldIsolateContextBetweenThreads() throws InterruptedException {
        // given
        TenantContext.set("main-thread-tenant");

        final String[] threadTenantId = {null};
        final boolean[] contextSetInThread = {false};

        // when
        final Thread thread = new Thread(() -> {
            contextSetInThread[0] = TenantContext.isSet();
            threadTenantId[0] = TenantContext.getOrNull();
        });
        thread.start();
        thread.join();

        // then
        assertThat(TenantContext.get()).isEqualTo("main-thread-tenant");

        assertThat(contextSetInThread[0]).isTrue();
        assertThat(threadTenantId[0]).isEqualTo("main-thread-tenant");
    }
}