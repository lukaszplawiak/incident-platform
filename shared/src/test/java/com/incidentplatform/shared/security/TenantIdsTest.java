package com.incidentplatform.shared.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TenantIds (backlog #0-92)")
class TenantIdsTest {

    @ParameterizedTest
    @ValueSource(strings = {"acme", "platform-operator", "system", "a1b", "x2-y3-z4",
            "abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz0"})
    @DisplayName("slugs of 3-63 characters are valid")
    void valid(String tenantId) {
        assertThat(TenantIds.isValid(tenantId)).isTrue();
        assertThat(TenantIds.requireValid(tenantId)).isEqualTo(tenantId);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ab", "-acme", "acme-", "Acme", "ac me", "ac_me", "acme\n", "evil\nFAKE",
            "a‮b", "acme:1", "abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz01", "ąćę"})
    @DisplayName("anything else is invalid, refused without quoting the value")
    void invalid(String tenantId) {
        assertThat(TenantIds.isValid(tenantId)).isFalse();
        assertThatThrownBy(() -> TenantIds.requireValid(tenantId))
                .isInstanceOf(InvalidTenantIdException.class)
                .hasMessage("Invalid tenant id: " + TenantIds.RULE);
    }
}
