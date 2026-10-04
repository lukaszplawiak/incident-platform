package com.incidentplatform.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which auth emails carry a token (backlog #0-83 added the token-less MFA notices). */
@DisplayName("AuthEmailType")
class AuthEmailTypeTest {

    @Test
    @DisplayName("API_KEY_CREATED (backlog #0-89) and MFA_RECOVERY_REQUESTED (backlog #0-90) are never "
            + "superseded by a newer request")
    void supersededByNewer() {
        for (final AuthEmailType type : AuthEmailType.values()) {
            assertThat(type.supersededByNewer()).as(type.name()).isEqualTo(
                    type != AuthEmailType.API_KEY_CREATED && type != AuthEmailType.MFA_RECOVERY_REQUESTED);
        }
    }

    @Test
    @DisplayName("invite and password reset carry their own token type")
    void tokenCarryingTypes() {
        assertThat(AuthEmailType.INVITE.carriesToken()).isTrue();
        assertThat(AuthEmailType.INVITE.tokenType()).isEqualTo(AuthToken.Type.INVITE);
        assertThat(AuthEmailType.PASSWORD_RESET.carriesToken()).isTrue();
        assertThat(AuthEmailType.PASSWORD_RESET.tokenType()).isEqualTo(AuthToken.Type.PASSWORD_RESET);
    }

    @Test
    @DisplayName("an MFA recovery notice carries its cancel link, its completion a password-reset link (backlog #0-90)")
    void mfaRecoveryTypesCarryTokens() {
        assertThat(AuthEmailType.MFA_RECOVERY_REQUESTED.carriesToken()).isTrue();
        assertThat(AuthEmailType.MFA_RECOVERY_REQUESTED.tokenType()).isEqualTo(AuthToken.Type.MFA_RECOVERY_CANCEL);
        assertThat(AuthEmailType.MFA_RECOVERY_COMPLETED.carriesToken()).isTrue();
        assertThat(AuthEmailType.MFA_RECOVERY_COMPLETED.tokenType()).isEqualTo(AuthToken.Type.PASSWORD_RESET);
    }

    @ParameterizedTest
    @EnumSource(value = AuthEmailType.class, names = {"MFA_ENABLED", "MFA_DISABLED", "MFA_RESET", "API_KEY_CREATED"})
    @DisplayName("a security notice carries no token, and asking for its token type is a bug")
    void notificationsCarryNoToken(AuthEmailType type) {
        assertThat(type.carriesToken()).isFalse();
        assertThatThrownBy(type::tokenType)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carries no token");
    }
}
