package com.incidentplatform.auth.api;

import com.incidentplatform.auth.service.TenantStatusBusyException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP answer to a sign-in that waited too long for a suspension in
 * progress (backlog #0-82, review): 503 with Retry-After. The shared
 * {@link GlobalExceptionHandler} is registered first on purpose: it handles the
 * {@code BusinessException} this extends, without the header, so the test
 * fails if {@link TenantStatusBusyHandler} loses its precedence.
 */
@DisplayName("TenantStatusBusyHandler")
class TenantStatusBusyHandlerTest {

    @RestController
    static class BusyController {
        @PostMapping("/api/v1/auth/refresh")
        void refresh() {
            throw new TenantStatusBusyException(Duration.ofSeconds(5));
        }

        @PostMapping("/api/v1/auth/mfa/verify")
        void deadlocked() {
            // What Spring makes of PostgreSQL's 40P01 and 55P03.
            throw new org.springframework.dao.CannotAcquireLockException("deadlock detected; SQL [select ...]");
        }
    }

    @Test
    @DisplayName("a lost lock (deadlock, lock timeout) is 503 + Retry-After + RESOURCE_BUSY, not the shared 500 "
            + "(backlog #0-82 step 2), and the SQL stays out of the body")
    void lockLostIs503() throws Exception {
        final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new BusyController())
                .setControllerAdvice(new GlobalExceptionHandler(), new TenantStatusBusyHandler())
                .build();

        mockMvc.perform(post("/api/v1/auth/mfa/verify"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.RESOURCE_BUSY))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("select"))));
    }

    @Test
    @DisplayName("503 + Retry-After + AUTHENTICATION_UNAVAILABLE, ahead of the shared handler")
    void busyIs503WithRetryAfter() throws Exception {
        final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new BusyController())
                .setControllerAdvice(new GlobalExceptionHandler(), new TenantStatusBusyHandler())
                .build();

        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.AUTHENTICATION_UNAVAILABLE));
    }
}
