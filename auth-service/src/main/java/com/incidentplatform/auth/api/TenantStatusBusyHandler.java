package com.incidentplatform.auth.api;

import com.incidentplatform.auth.service.TenantStatusBusyException;
import com.incidentplatform.shared.dto.ErrorResponse;
import com.incidentplatform.shared.exception.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Duration;
import java.util.Objects;

/**
 * Answers {@link TenantStatusBusyException} with 503 and Retry-After (backlog
 * #0-82). shared's {@code GlobalExceptionHandler} would answer the same status
 * from the {@code BusinessException} it extends, but without the header a
 * client needs to know when to try again. Ordered first: Spring picks the first
 * advice with any matching handler, not the most specific one across advices.
 *
 * <h2>Added (backlog #0-82, step 2): a lost lock is a 503 too</h2>
 * A sign-in that loses a deadlock to a suspension's session cleanup, or any
 * other request of auth-service whose lock wait timed out or was chosen as a
 * deadlock's victim, reached {@code GlobalExceptionHandler}'s catch-all as a
 * 500 (found in the review of step 1). Nothing is wrong with the request and
 * its transaction rolled back whole: the same request succeeds a moment later.
 * So 503 + Retry-After, {@code RESOURCE_BUSY}. A caller that maps a busy row to
 * something else catches it first (the API key creation limit's 429), so this
 * sees only the lock failures nobody expected.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantStatusBusyHandler {

    private static final Logger log = LoggerFactory.getLogger(TenantStatusBusyHandler.class);

    static final Duration LOCK_FAILURE_RETRY_AFTER = Duration.ofSeconds(5);

    @ExceptionHandler(TenantStatusBusyException.class)
    ResponseEntity<ErrorResponse> busy(TenantStatusBusyException busy) {
        return ResponseEntity.status(busy.getHttpStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, busy.retryAfter().toSeconds())))
                .body(ErrorResponse.of(busy.getHttpStatus().value(), busy.getErrorCode(), busy.getMessage(),
                        requestId()));
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ErrorResponse> lockLost(PessimisticLockingFailureException e) {
        // The type only: the message carries the SQL.
        log.warn("Request lost a lock (timeout or deadlock), answered 503: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(LOCK_FAILURE_RETRY_AFTER.toSeconds()))
                .body(ErrorResponse.of(HttpStatus.SERVICE_UNAVAILABLE.value(), ErrorCodes.RESOURCE_BUSY,
                        "Another change to the same data was in progress. Retry in a few seconds.",
                        requestId()));
    }

    private static String requestId() {
        return Objects.requireNonNullElse(MDC.get("requestId"), "unknown");
    }
}
