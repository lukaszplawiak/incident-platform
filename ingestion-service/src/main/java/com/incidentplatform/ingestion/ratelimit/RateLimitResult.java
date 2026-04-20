package com.incidentplatform.ingestion.ratelimit;

public record RateLimitResult(
        boolean allowed,
        String reason,
        long retryAfterSeconds
) {
    public static RateLimitResult permit() {
        return new RateLimitResult(true, null, 0);
    }

    public static RateLimitResult tenantLimited(String tenantId) {
        return new RateLimitResult(false,
                "Rate limit exceeded for tenant: " + tenantId, 1L);
    }

    public static RateLimitResult ipLimited(String clientIp) {
        return new RateLimitResult(false,
                "Rate limit exceeded for IP: " + clientIp, 1L);
    }
}