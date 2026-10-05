package com.incidentplatform.auth.api;

import com.incidentplatform.auth.service.SignInFlow;
import com.incidentplatform.auth.service.SignInRefusals;
import com.incidentplatform.auth.service.TenantSuspendedSignInException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.exception.GlobalExceptionHandler;
import com.incidentplatform.shared.security.TenantAccess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SignInRefusalHandler} (backlog #0-82, step 2): the refusal is recorded
 * and answered 403 with the suspension's code. The shared handler is registered
 * first on purpose, as in {@code TenantStatusBusyHandlerTest}: it would answer
 * the {@code BusinessException} the refusal extends without recording it.
 */
@DisplayName("SignInRefusalHandler")
class SignInRefusalHandlerTest {

    private static final UUID USER = UUID.randomUUID();

    @RestController
    static class RefusingController {
        @PostMapping("/api/v1/auth/login")
        void login() {
            throw new TenantSuspendedSignInException("acme", USER, SignInFlow.LOGIN, TenantAccess.NONE);
        }
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<SignInRefusals> provider(SignInRefusals refusals) {
        final org.springframework.beans.factory.ObjectProvider<SignInRefusals> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.BDDMockito.given(provider.getObject()).willReturn(refusals);
        return provider;
    }

    @Test
    @DisplayName("records the refusal, then 403 TENANT_SUSPENDED, ahead of the shared handler")
    void recordsAndRefuses() throws Exception {
        final SignInRefusals refusals = mock(SignInRefusals.class);
        org.mockito.BDDMockito.given(refusals.record(any())).willReturn(new SignInRefusals.Outcome.Recorded());
        final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RefusingController())
                .setControllerAdvice(new GlobalExceptionHandler(), new SignInRefusalHandler(provider(refusals)))
                .build();

        mockMvc.perform(post("/api/v1/auth/login"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.TENANT_SUSPENDED));
        then(refusals).should().record(any(TenantSuspendedSignInException.class));
    }

    @Test
    @DisplayName("over the user's limit: 429 with Retry-After, not the suspension's 403 (review of step 2)")
    void throttledIs429() throws Exception {
        final SignInRefusals refusals = mock(SignInRefusals.class);
        org.mockito.BDDMockito.given(refusals.record(any()))
                .willReturn(new SignInRefusals.Outcome.Throttled(java.time.Duration.ofSeconds(420)));
        final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RefusingController())
                .setControllerAdvice(new GlobalExceptionHandler(), new SignInRefusalHandler(provider(refusals)))
                .build();

        mockMvc.perform(post("/api/v1/auth/login"))
                .andExpect(status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Retry-After", "420"))
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.TOO_MANY_FAILED_AUTHENTICATIONS));
    }
}
