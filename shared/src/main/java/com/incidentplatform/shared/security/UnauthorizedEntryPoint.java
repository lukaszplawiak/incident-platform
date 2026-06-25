package com.incidentplatform.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.shared.dto.ErrorResponse;
import com.incidentplatform.shared.exception.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * {@link AuthenticationEntryPoint} that writes a {@code 401 Unauthorized}
 * response in the same {@link ErrorResponse} JSON format used by
 * {@link com.incidentplatform.shared.exception.GlobalExceptionHandler}.
 *
 * <h2>Why this is needed</h2>
 * Spring Security's {@code ExceptionTranslationFilter} intercepts
 * authentication failures <em>before</em> the request reaches
 * {@code DispatcherServlet}. This means {@code @ExceptionHandler} methods
 * in {@code GlobalExceptionHandler} are never invoked for unauthenticated
 * requests — the filter chain handles them directly and writes the response
 * itself. Without an explicit {@code AuthenticationEntryPoint}, Spring falls
 * back to {@code Http403ForbiddenEntryPoint}, which returns
 * {@code 403 Forbidden} regardless of whether the caller provided a token.
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>{@code 401 Unauthorized} — the request carries no credentials or the
 *       token is missing/expired/malformed. The caller <em>could</em> retry
 *       with valid credentials.</li>
 *   <li>{@code 403 Forbidden} — the caller is authenticated but lacks
 *       the required role. Retrying with the same credentials will not help.</li>
 * </ul>
 * This class restores the correct 401 semantic for unauthenticated requests,
 * while 403 continues to be handled by {@code GlobalExceptionHandler} for
 * requests that are authenticated but lack sufficient permissions.
 *
 * <h2>Response format</h2>
 * Identical to every other error response in the platform:
 * <pre>{@code
 * {
 *   "status": 401,
 *   "errorCode": "UNAUTHORIZED",
 *   "message": "Authentication required. Please provide a valid JWT token.",
 *   "requestId": "a1b2c3d4-...",
 *   "timestamp": "2026-06-25T12:00:00Z"
 * }
 * }</pre>
 *
 * <h2>Registration</h2>
 * Registered in {@link SharedSecurityAutoConfiguration#buildCommonSecurity}
 * via {@code .exceptionHandling(ex -> ex.authenticationEntryPoint(...))} so
 * it applies to every service that calls {@code buildCommonSecurity}, without
 * each service needing to configure it individually.
 */
@Component
public class UnauthorizedEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log =
            LoggerFactory.getLogger(UnauthorizedEntryPoint.class);

    private static final String MDC_REQUEST_ID = "requestId";

    private final ObjectMapper objectMapper;

    public UnauthorizedEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        log.warn("Unauthenticated request to: {}", request.getRequestURI());

        final String requestId = resolveRequestId(response);

        final ErrorResponse body = ErrorResponse.of(
                HttpStatus.UNAUTHORIZED.value(),
                ErrorCodes.UNAUTHORIZED,
                "Authentication required. Please provide a valid JWT token.",
                requestId
        );

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }

    /**
     * Resolves the request ID from {@code X-Request-Id} response header (set
     * by {@link JwtAuthFilter} early in the filter chain) or falls back to
     * the MDC value. Returns {@code "unknown"} if neither is available.
     */
    private String resolveRequestId(HttpServletResponse response) {
        final String fromHeader = response.getHeader("X-Request-Id");
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader;
        }
        final String fromMdc = MDC.get(MDC_REQUEST_ID);
        return fromMdc != null ? fromMdc : "unknown";
    }
}