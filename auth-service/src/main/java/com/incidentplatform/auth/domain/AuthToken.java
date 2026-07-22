package com.incidentplatform.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_tokens")
public class AuthToken {

    public enum Type {
        INVITE,
        PASSWORD_RESET,
        /** Refresh token — rotated on every use, 30-day TTL by default. */
        REFRESH,

        /**
         * MFA session token — issued after successful password verification
         * when user has MFA enabled. Short TTL (5 minutes). Single-use.
         * Exchanged for accessToken + refreshToken via POST /auth/mfa/verify.
         *
         * <p>This is an opaque token (like REFRESH), not a JWT — it does not
         * grant access to any API endpoint. It only identifies the user for
         * the second factor verification step.
         */
        MFA_SESSION,
        /**
         * MFA setup-required token — issued after successful password
         * verification when the tenant requires MFA but the user has not
         * configured it yet (see AuthService.login()). Distinct from
         * MFA_SESSION: that token is for verifying an *already-configured*
         * second factor; this one is for completing setup when the user has
         * none yet and therefore holds no access token at all. Not consumed
         * by the setup step (POST /mfa/setup-required may be retried),
         * consumed only by the final POST /mfa/enable-required, which also
         * completes login by issuing real access/refresh tokens. 10-minute
         * TTL — longer than MFA_SESSION's 5, since installing/configuring an
         * authenticator app takes longer than typing a code from one already
         * set up.
         */
        MFA_SETUP_REQUIRED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private Type type;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AuthToken() {}

    public static AuthToken create(User user, String tenantId,
                                   String tokenHash, Type type,
                                   Instant expiresAt) {
        final AuthToken token = new AuthToken();
        token.user = user;
        token.tenantId = tenantId;
        token.tokenHash = tokenHash;
        token.type = type;
        token.expiresAt = expiresAt;
        return token;
    }

    /**
     * Test fixture factory — see {@link User#forTesting} for rationale.
     */
    public static AuthToken forTesting(User user, String tenantId,
                                       String tokenHash, Type type,
                                       Instant expiresAt, Instant usedAt) {
        final AuthToken token = create(user, tenantId, tokenHash, type, expiresAt);
        token.usedAt = usedAt;
        return token;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isValid() {
        return !isExpired() && !isUsed();
    }

    public void markUsed() {
        this.usedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public String getTenantId() { return tenantId; }
    public String getTokenHash() { return tokenHash; }
    public Type getType() { return type; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
}