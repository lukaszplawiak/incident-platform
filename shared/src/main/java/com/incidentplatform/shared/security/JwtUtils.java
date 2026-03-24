package com.incidentplatform.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JwtUtils {

    private static final Logger log = LoggerFactory.getLogger(JwtUtils.class);

    public static final String CLAIM_TENANT_ID = "tenantId";
    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_EMAIL = "email";

    private final SecretKey secretKey;

    private final long expirationMs;

    public JwtUtils(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms:86400000}") long expirationMs) {

        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException(
                    "JWT secret must be at least 32 characters (256 bits). " +
                            "Generate with: openssl rand -base64 64"
            );
        }

        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;

        log.info("JwtUtils initialized, token expiration: {} ms", expirationMs);
    }

    public String generateToken(UUID userId, String tenantId,
                                String email, List<String> roles) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expirationMs);

        String token = Jwts.builder()
                .subject(userId.toString())
                .claim(CLAIM_TENANT_ID, tenantId)
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_ROLES, roles)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(secretKey)
                .compact();

        log.info("JWT token generated for userId: {}, tenantId: {}, expiration: {}",
                userId, tenantId, expiration);

        return token;
    }

    public Optional<Claims> validateAndGetClaims(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return Optional.of(claims);

        } catch (ExpiredJwtException e) {
            log.warn("JWT token expired for subject: {}", e.getClaims().getSubject());
            return Optional.empty();

        } catch (JwtException e) {
            log.warn("Invalid JWT token detected: {}", e.getMessage());
            return Optional.empty();

        } catch (Exception e) {
            log.error("Unexpected error during JWT validation", e);
            return Optional.empty();
        }
    }

    public Optional<String> extractTenantId(Claims claims) {
        return Optional.ofNullable(claims.get(CLAIM_TENANT_ID, String.class));
    }

    public Optional<UUID> extractUserId(Claims claims) {
        try {
            return Optional.of(UUID.fromString(claims.getSubject()));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid userId format in JWT subject: {}", claims.getSubject());
            return Optional.empty();
        }
    }

    public Optional<String> extractEmail(Claims claims) {
        return Optional.ofNullable(claims.get(CLAIM_EMAIL, String.class));
    }

    @SuppressWarnings("unchecked")
    public List<String> extractRoles(Claims claims) {
        Object rolesObj = claims.get(CLAIM_ROLES);
        if (rolesObj instanceof List<?> rolesList) {
            // Pattern matching instanceof — Java 16+ feature
            return rolesList.stream()
                    .filter(r -> r instanceof String)
                    .map(r -> (String) r)
                    .toList();
        }
        return List.of();
    }
}