package com.incidentplatform.auth.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.auth.service.MfaSessionStatusService.Status;
import com.incidentplatform.shared.exception.ErrorCodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PlatformAccessDeniedHandler (backlog #0-83)")
class PlatformAccessDeniedHandlerTest {

    // As Spring's own ObjectMapper: ErrorResponse carries an Instant.
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final PlatformAccessDeniedHandler handler = new PlatformAccessDeniedHandler(objectMapper);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private JsonNode refuse(Status status, MockHttpServletResponse response) throws Exception {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(PlatformAccess.MFA_REQUIRED_ATTRIBUTE, status);
        handler.handle(request, response, new AccessDeniedException("denied"));
        return objectMapper.readTree(response.getContentAsString());
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = "ACCEPTED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("a refusal for an MFA condition names it and points to the guide, without enrolment steps")
    void mfaRefusal(Status status) throws Exception {
        final MockHttpServletResponse response = new MockHttpServletResponse();

        final JsonNode body = refuse(status, response);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body.get("errorCode").asText()).isEqualTo(ErrorCodes.FORBIDDEN);
        assertThat(body.get("message").asText())
                .isEqualTo(PlatformAccessDeniedHandler.message(status))
                .contains("docs/tenant-provisioning.md")
                .doesNotContain("/mfa/setup").doesNotContain("/mfa/enable");
        assertThat(body.get("requestId").asText()).isEqualTo(response.getHeader("X-Request-Id"));
    }

    @Test
    @DisplayName("session conditions get their own message; a factor too new and an unsent notice share one")
    void messages() {
        assertThat(PlatformAccessDeniedHandler.message(Status.NO_MFA))
                .isNotEqualTo(PlatformAccessDeniedHandler.message(Status.MFA_TOO_OLD))
                .isNotEqualTo(PlatformAccessDeniedHandler.message(Status.MFA_ENROLLED_TOO_RECENTLY));
        // Telling a password thief whether the owner was warned yet would tell them when to try.
        assertThat(PlatformAccessDeniedHandler.message(Status.MFA_NOTICE_NOT_DELIVERED))
                .isEqualTo(PlatformAccessDeniedHandler.message(Status.MFA_ENROLLED_TOO_RECENTLY))
                .contains("disable and enable MFA again");
    }

    @Test
    @DisplayName("uses the request's id from the MDC when there is one, so the answer matches the log")
    void requestIdFromMdc() throws Exception {
        MDC.put("requestId", "req-123");
        final MockHttpServletResponse response = new MockHttpServletResponse();

        final JsonNode body = refuse(Status.NO_MFA, response);

        assertThat(body.get("requestId").asText()).isEqualTo("req-123");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("req-123");
    }

    @Test
    @DisplayName("any other refusal gets Spring Security's default answer, with no message")
    void otherDenial() throws Exception {
        final MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(new MockHttpServletRequest(), response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).doesNotContain("MFA");
    }
}
