package com.incidentplatform.auth.api;

import com.incidentplatform.auth.service.SignInRefusals;
import com.incidentplatform.auth.service.TenantSuspendedSignInException;
import com.incidentplatform.shared.dto.ErrorResponse;
import com.incidentplatform.shared.exception.ErrorCodes;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Objects;

/**
 * Answers a sign-in refused for a suspended tenant (backlog #0-82, step 2), and
 * first records it ({@link SignInRefusals}). Here, in the web layer, because
 * by now the service method's transaction has rolled back (the consumed token
 * comes back, nothing of the sign-in remains), so the audit event is not lost
 * with it. The answer is the one shared's {@code GlobalExceptionHandler} gives a
 * {@code BusinessException}; ordered first, since Spring picks the first advice
 * with any matching handler. Past the user's limit of such refusals it answers
 * 429 with Retry-After instead, unaudited ({@link SignInRefusals}).
 *
 * <p>{@link SignInRefusals} is looked up when a refusal happens, not injected:
 * a {@code @WebMvcTest} slice loads this advice but not the services, and
 * would otherwise fail to start in every controller test. A refusal with no
 * recorder fails (no unaudited refusal), so a slice test that provokes one
 * must provide it.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SignInRefusalHandler {

    private final ObjectProvider<SignInRefusals> signInRefusals;

    public SignInRefusalHandler(ObjectProvider<SignInRefusals> signInRefusals) {
        this.signInRefusals = signInRefusals;
    }

    @ExceptionHandler(TenantSuspendedSignInException.class)
    ResponseEntity<ErrorResponse> refused(TenantSuspendedSignInException refusal) {
        final String requestId = Objects.requireNonNullElse(MDC.get("requestId"), "unknown");
        return switch (signInRefusals.getObject().record(refusal)) {
            case SignInRefusals.Outcome.Recorded recorded -> ResponseEntity.status(refusal.getHttpStatus())
                    .body(ErrorResponse.of(refusal.getHttpStatus().value(), refusal.getErrorCode(),
                            refusal.getMessage(), requestId));
            // Over the user's limit of refusals (review of step 2): the attempt is
            // bounded, not answered and audited again.
            case SignInRefusals.Outcome.Throttled throttled -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(throttled.retryAfter().toSeconds()))
                    .body(ErrorResponse.of(HttpStatus.TOO_MANY_REQUESTS.value(),
                            ErrorCodes.TOO_MANY_FAILED_AUTHENTICATIONS,
                            "Too many sign-in attempts. Retry later.", requestId));
        };
    }
}
