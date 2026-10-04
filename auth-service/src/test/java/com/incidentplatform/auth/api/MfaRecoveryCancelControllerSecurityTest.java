package com.incidentplatform.auth.api;

import com.incidentplatform.auth.config.SecurityConfig;
import com.incidentplatform.auth.service.MfaRecoveryService;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.security.ApiKeyAuthFilter;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.ServiceTokenProvider;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The account's cancel link of an operator MFA recovery (backlog #0-90):
 * reachable without a JWT (the owner of a locked-out account has only the
 * emailed token), POST only, and the token decides everything.
 */
@WebMvcTest(MfaRecoveryCancelController.class)
@Import({SecurityConfig.class, UnauthorizedEntryPoint.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.access-token-ttl=PT15M",
        "jwt.service-token-ttl=PT1H",
        "jwt.refresh-token-ttl=P30D",
        "spring.application.name=auth-service",
        "security.cors.allowed-origins=http://localhost:4200",
        "mfa.encryption-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWRldi1vbmx5ISE=",
        "slack.encryption-key=c2xhY2sta2V5LTMyLWJ5dGVzLWZvci1kZXYtb25seSE="
})
@DisplayName("MfaRecoveryCancelController — security")
class MfaRecoveryCancelControllerSecurityTest {

    private static final String CANCEL = "/api/v1/auth/mfa-recovery/cancel";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private MfaRecoveryService recoveryService;
    @MockitoBean private JwtUtils jwtUtils;
    @MockitoBean private ServiceTokenProvider serviceTokenProvider;
    @MockitoBean private ApiKeyAuthFilter.ApiKeyLookupService apiKeyLookupService;

    @Test
    @DisplayName("no JWT needed: a valid token cancels — 204, also when nothing was left open")
    void publicWithToken() throws Exception {
        given(recoveryService.cancelByAccount("raw-token")).willReturn(true, false);
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(CANCEL).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"raw-token\"}"))
                    .andExpect(status().isNoContent());
        }
    }

    @Test
    @DisplayName("an invalid token — 401 from the token service")
    void invalidToken() throws Exception {
        willThrow(new BusinessException(ErrorCodes.UNAUTHORIZED, "invalid", HttpStatus.UNAUTHORIZED))
                .given(recoveryService).cancelByAccount(anyString());
        mockMvc.perform(post(CANCEL).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"bad\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("no token in the body — 400, the service is not called")
    void missingToken() throws Exception {
        mockMvc.perform(post(CANCEL).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"\"}"))
                .andExpect(status().isBadRequest());
        then(recoveryService).should(never()).cancelByAccount(anyString());
    }

    @Test
    @DisplayName("only POST is public: GET is not opened by the permitAll line")
    void getIsNotPublic() throws Exception {
        mockMvc.perform(get(CANCEL)).andExpect(status().isUnauthorized());
    }
}
