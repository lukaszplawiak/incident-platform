package com.incidentplatform.auth.service;

import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.UserPrincipal;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class LogoutService {

    private static final Logger log = LoggerFactory.getLogger(LogoutService.class);

    private final JwtUtils jwtUtils;
    private final TokenRevocationService revocationService;

    public LogoutService(JwtUtils jwtUtils,
                         TokenRevocationService revocationService) {
        this.jwtUtils = jwtUtils;
        this.revocationService = revocationService;
    }

    /**
     * Revokes the current session token.
     *
     * <p>The raw token string is needed (not just the principal) because the
     * jti claim is not stored in {@link UserPrincipal} — extracting it again
     * from the token ensures we revoke exactly the token that was submitted
     * in this request, not a different token for the same user.
     *
     * @param rawToken the raw JWT string from the Authorization header
     * @param principal the authenticated user — used only for logging
     */
    public void logout(String rawToken, UserPrincipal principal) {
        final Claims claims = jwtUtils.validateAndGetClaims(rawToken)
                .orElseThrow(() -> new BusinessException(
                        ErrorCodes.UNAUTHORIZED,
                        "Invalid token",
                        HttpStatus.UNAUTHORIZED));

        final String jti = jwtUtils.extractJti(claims)
                .orElseThrow(() -> new BusinessException(
                        ErrorCodes.UNAUTHORIZED,
                        "Token does not contain a jti claim — cannot revoke",
                        HttpStatus.UNAUTHORIZED));

        final Date expiresAt = jwtUtils.extractExpiration(claims)
                .orElseThrow(() -> new BusinessException(
                        ErrorCodes.UNAUTHORIZED,
                        "Token does not contain expiration",
                        HttpStatus.UNAUTHORIZED));

        revocationService.revoke(jti, expiresAt);

        log.info("Logout: userId={}, tenant={}, jti={}",
                principal.userId(), principal.tenantId(), jti);
    }
}