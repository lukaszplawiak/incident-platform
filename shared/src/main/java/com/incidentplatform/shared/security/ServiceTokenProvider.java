package com.incidentplatform.shared.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ServiceTokenProvider {

    private static final Logger log =
            LoggerFactory.getLogger(ServiceTokenProvider.class);

    private static final long REFRESH_BUFFER_SECONDS = 300L; // 5 minut

    private final JwtUtils jwtUtils;
    private final String serviceName;
    private final long expirationMs;

    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt;

    public ServiceTokenProvider(
            JwtUtils jwtUtils,
            @Value("${spring.application.name:unknown-service}") String serviceName,
            @Value("${jwt.expiration-ms:86400000}") long expirationMs) {
        this.jwtUtils = jwtUtils;
        this.serviceName = serviceName;
        this.expirationMs = expirationMs;
    }

    public String getToken() {
        if (needsRefresh()) {
            refresh();
        }
        return cachedToken;
    }

    private boolean needsRefresh() {
        if (cachedToken == null || tokenExpiresAt == null) {
            return true;
        }
        return Instant.now().isAfter(
                tokenExpiresAt.minusSeconds(REFRESH_BUFFER_SECONDS));
    }

    private synchronized void refresh() {
        if (!needsRefresh()) {
            return;
        }

        cachedToken = jwtUtils.generateServiceToken(serviceName);
        tokenExpiresAt = Instant.now()
                .plusMillis(expirationMs);

        log.debug("Service token refreshed for service={}, expiresAt={}",
                serviceName, tokenExpiresAt);
    }
}