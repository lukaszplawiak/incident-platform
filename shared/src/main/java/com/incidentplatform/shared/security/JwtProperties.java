package com.incidentplatform.shared.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Strongly-typed, validated JWT configuration for all incident-platform services.
 *
 * <p>Replaces three scattered {@code @Value} injections in {@link JwtUtils}:
 * <ul>
 *   <li>{@code @Value("${jwt.secret}")}
 *   <li>{@code @Value("${jwt.expiration-ms:86400000}")} — was {@code long}, now {@link Duration}
 *   <li>{@code @Value("${jwt.service-expiration:PT1H}")}
 * </ul>
 *
 * <h2>Why Duration instead of long milliseconds</h2>
 * {@code long expirationMs} is ambiguous — the unit is only in the name,
 * not in the type. A misconfigured value like {@code jwt.access-token-ttl=PT15M}
 * (correct format) in a {@code long} field would cause a {@code NumberFormatException}
 * at runtime rather than a clean startup error. {@link Duration} is self-documenting,
 * unit-safe, and Spring Boot converts ISO-8601 strings ({@code PT15M}, {@code P30D})
 * automatically via {@code ApplicationConversionService}.
 *
 * <h2>Fail-fast validation at startup</h2>
 * {@code @Validated} triggers Bean Validation when the application context starts.
 * A misconfigured value causes a {@code BindValidationException} with a descriptive
 * message before any request is processed — instead of a cryptic error on the
 * first login attempt. Duration range validation (min/max TTL) is handled in
 * {@link JwtUtils#validateConfiguration()} via {@code @PostConstruct} because
 * Bean Validation lacks built-in {@link Duration} range constraints without
 * a third-party library dependency.
 *
 * <h2>Why a record</h2>
 * Configuration is immutable by nature — once bound at startup it never changes.
 * A record enforces this at the language level and eliminates getters, equals,
 * hashCode, and toString boilerplate. Spring Boot supports
 * {@code @ConfigurationProperties} records since 2.6.
 *
 * <h2>YAML configuration</h2>
 * <pre>{@code
 * jwt:
 *   secret: ${JWT_SECRET}
 *   access-token-ttl: ${JWT_ACCESS_TOKEN_TTL:PT15M}    # ISO-8601 duration
 *   service-token-ttl: ${JWT_SERVICE_TOKEN_TTL:PT1H}   # ISO-8601 duration
 * }</pre>
 */
@ConfigurationProperties(prefix = "jwt")
@Validated
public record JwtProperties(

        /**
         * HMAC-SHA secret for signing JWTs. Must be at least 64 characters
         * (512 bits) for HS512. Byte-length validation is performed in
         * {@link JwtUtils#JwtUtils(JwtProperties)} because {@code @Size}
         * counts characters, not bytes.
         * <p>Generate with: {@code openssl rand -base64 64}
         */
        @NotBlank(message = "jwt.secret must not be blank")
        String secret,

        /**
         * Access token TTL — how long a user JWT is valid after login.
         * Default: PT15M (15 minutes). Short TTL limits exposure of stolen
         * tokens; session continuity is handled by the refresh token flow
         * ({@code POST /api/v1/auth/refresh}).
         * <p>Range [PT1M .. PT24H] validated in {@link JwtUtils#validateConfiguration()}.
         */
        @NotNull(message = "jwt.access-token-ttl must not be null")
        Duration accessTokenTtl,

        /**
         * Service token TTL — for inter-service authentication via
         * {@link ServiceTokenProvider}. Default: PT1H. Longer than
         * {@link #accessTokenTtl} since service tokens are managed
         * internally and rotated automatically before expiry.
         * <p>Range [PT10M .. PT24H] validated in {@link JwtUtils#validateConfiguration()}.
         */
        @NotNull(message = "jwt.service-token-ttl must not be null")
        Duration serviceTokenTtl,

        /**
         * Refresh token lifetime. Long TTL enables persistent sessions
         * without requiring the user to re-enter their password.
         * Refresh tokens are rotated on every use — if stolen and used,
         * the legitimate user is alerted on their next refresh attempt.
         * Default: P30D (30 days).
         * <p>Range [P1D .. P365D] validated in JwtUtils@PostConstruct.
         */
        @NotNull(message = "jwt.refresh-token-ttl must not be null")
        Duration refreshTokenTtl

) {}