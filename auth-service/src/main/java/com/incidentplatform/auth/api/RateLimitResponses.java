package com.incidentplatform.auth.api;

import com.incidentplatform.auth.ratelimit.RateLimitDecision;
import com.incidentplatform.auth.ratelimit.RateLimitRefusedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The answer to a refused rate limit: 429 when a limit refuses, 503 when it
 * cannot be checked, both with Retry-After and no body (backlog #0-83, #0-88,
 * #0-89). One place, so every controller that runs a limited operation
 * answers the same.
 */
final class RateLimitResponses {

    private RateLimitResponses() {
    }

    static ResponseEntity<Void> refused(RateLimitRefusedException refused) {
        final RateLimitDecision decision = refused.decision();
        final HttpStatus status = decision.outcome() == RateLimitDecision.Outcome.LIMITED
                ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()))
                .build();
    }
}
