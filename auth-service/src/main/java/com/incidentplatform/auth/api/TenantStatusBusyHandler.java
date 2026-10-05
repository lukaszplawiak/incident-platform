package com.incidentplatform.auth.api;

import com.incidentplatform.auth.service.TenantStatusBusyException;
import com.incidentplatform.shared.dto.ErrorResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Objects;

/**
 * Answers {@link TenantStatusBusyException} with 503 and Retry-After (backlog
 * #0-82). shared's {@code GlobalExceptionHandler} would answer the same status
 * from the {@code BusinessException} it extends, but without the header a
 * client needs to know when to try again. Ordered first: Spring picks the first
 * advice with any matching handler, not the most specific one across advices.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantStatusBusyHandler {

    @ExceptionHandler(TenantStatusBusyException.class)
    ResponseEntity<ErrorResponse> busy(TenantStatusBusyException busy) {
        return ResponseEntity.status(busy.getHttpStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, busy.retryAfter().toSeconds())))
                .body(ErrorResponse.of(busy.getHttpStatus().value(), busy.getErrorCode(), busy.getMessage(),
                        Objects.requireNonNullElse(MDC.get("requestId"), "unknown")));
    }
}
