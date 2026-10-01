package com.incidentplatform.auth.ratelimit;

/**
 * A service refused an operation by a fail-closed rate limit (backlog #0-88:
 * the admin MFA reset, checked inside the service as the last step before the
 * change, so only resets that would happen count). The controller turns it into 429 or 503 with
 * Retry-After, the answer the platform API gives (#0-83).
 */
public class RateLimitRefusedException extends RuntimeException {

    private final RateLimitDecision decision;

    public RateLimitRefusedException(RateLimitDecision decision) {
        super("Refused by rate limit: " + decision.outcome());
        if (decision.allowed()) {
            throw new IllegalArgumentException("an allowed decision is not a refusal");
        }
        this.decision = decision;
    }

    public RateLimitDecision decision() {
        return decision;
    }
}
