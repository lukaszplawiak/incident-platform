package com.incidentplatform.shared.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ReservedTenants")
class ReservedTenantsTest {

    @Test
    @DisplayName("reserves platform-operator and the legacy system tenant, case-insensitively")
    void reserved() {
        assertThat(ReservedTenants.isReserved("platform-operator")).isTrue();
        assertThat(ReservedTenants.isReserved("Platform-Operator")).isTrue();
        assertThat(ReservedTenants.isReserved(" system ")).isTrue();
        assertThat(ReservedTenants.isReserved("SYSTEM")).isTrue();
    }

    @Test
    @DisplayName("does not reserve ordinary tenant ids or null")
    void notReserved() {
        assertThat(ReservedTenants.isReserved("default")).isFalse();
        assertThat(ReservedTenants.isReserved("acme-corp")).isFalse();
        assertThat(ReservedTenants.isReserved("platform-operator-2")).isFalse();
        assertThat(ReservedTenants.isReserved(null)).isFalse();
    }

    @Test
    @DisplayName("requireNotReserved throws only for a reserved id")
    void requireNotReserved() {
        assertThatThrownBy(() -> ReservedTenants.requireNotReserved("platform-operator"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
        assertThatCode(() -> ReservedTenants.requireNotReserved("acme-corp"))
                .doesNotThrowAnyException();
    }
}
