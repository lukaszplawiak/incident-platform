package com.incidentplatform.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.auth.service.MfaSessionStatusService.Status;
import com.incidentplatform.shared.dto.ErrorResponse;
import com.incidentplatform.shared.exception.ErrorCodes;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;

import java.io.IOException;
import java.util.UUID;

/**
 * Says which MFA condition an operator admin refused by the platform API
 * failed (backlog #0-83), instead of a bare 403. Every other denial in
 * auth-service's filter chain is answered exactly as before, by Spring
 * Security's default handler.
 *
 * <p>The response says nothing a caller could not learn anyway: it is given
 * only to an authenticated admin of the platform-operator tenant
 * ({@link PlatformAccess} sets the marker after checking that). It does not
 * walk the caller through enrolling a factor (found in review: a password
 * alone must not be guided to a working second factor); it points to the
 * operator guide. A bean of {@link SecurityConfig}.
 */
public class PlatformAccessDeniedHandler implements AccessDeniedHandler {

    static final String GUIDE = " See docs/tenant-provisioning.md, \"Prerequisite: an operator admin\".";

    private final AccessDeniedHandler defaultHandler = new AccessDeniedHandlerImpl();
    private final ObjectMapper objectMapper;

    public PlatformAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException, ServletException {
        if (!(request.getAttribute(PlatformAccess.MFA_REQUIRED_ATTRIBUTE) instanceof Status status)) {
            defaultHandler.handle(request, response, accessDeniedException);
            return;
        }
        // The request's id if the logging filter set one, so the answer and the
        // WARN line in the log match.
        final String mdcRequestId = MDC.get("requestId");
        final String requestId = mdcRequestId != null ? mdcRequestId : UUID.randomUUID().toString();
        response.setHeader("X-Request-Id", requestId);
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ErrorResponse.of(HttpStatus.FORBIDDEN.value(), ErrorCodes.FORBIDDEN, message(status), requestId));
    }

    static String message(Status status) {
        return switch (status) {
            case NO_MFA -> "The platform API requires a login that completed MFA: log in with your "
                    + "authenticator code." + GUIDE;
            case MFA_TOO_OLD -> "The platform API requires a recent MFA login: this session verified MFA "
                    + "too long ago. Log in again with your authenticator code." + GUIDE;
            // One answer for both (found in review): telling a password thief
            // whether the owner has been warned yet would tell them when to
            // try. The exact status is in the WARN log.
            case MFA_ENROLLED_TOO_RECENTLY, MFA_NOTICE_NOT_DELIVERED -> "The platform API accepts a second "
                    + "factor only some time after the email announcing it was sent to the account's address. "
                    + "If that email never arrived, disable and enable MFA again to send a new one." + GUIDE;
            case ACCEPTED -> throw new IllegalStateException("an accepted session is not refused");
        };
    }
}
