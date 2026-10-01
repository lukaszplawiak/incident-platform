package com.incidentplatform.auth.ratelimit;

/**
 * What a rate-limited operation may do now, as answered by the fail-closed
 * limiters ({@link PlatformRateLimiter}, backlog #0-83, and
 * {@link MfaResetRateLimiter}, backlog #0-88). Shared so both map to the same
 * 429 / 503 with Retry-After.
 *
 * @param retryAfterSeconds for LIMITED and UNAVAILABLE; 0 when allowed
 */
public record RateLimitDecision(Outcome outcome, long retryAfterSeconds) {

    /** ALLOWED, refused by a limit (429), or refused because the limit cannot be checked (503). */
    public enum Outcome { ALLOWED, LIMITED, UNAVAILABLE }

    public static final RateLimitDecision ALLOWED = new RateLimitDecision(Outcome.ALLOWED, 0);

    public boolean allowed() {
        return outcome == Outcome.ALLOWED;
    }
}
