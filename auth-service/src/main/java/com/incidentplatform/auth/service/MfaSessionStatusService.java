package com.incidentplatform.auth.service;

import com.incidentplatform.auth.repository.AuthTokenRepository;
import com.incidentplatform.auth.repository.MfaSessionFacts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Whether a login session has a second factor the platform API accepts
 * (backlog #0-83), for {@code PlatformAccess}.
 *
 * <h2>Three conditions</h2>
 * <ol>
 *   <li>The session is live and completed MFA (a TOTP or backup code at
 *       login).</li>
 *   <li>It did so within {@code platform.mfa.max-session-age} (default 12 h):
 *       a refresh token lives 30 days, and the platform's widest capability
 *       should not ride on a second factor proven weeks ago.</li>
 *   <li>The email announcing the account's factor was sent at least
 *       {@code platform.mfa.enrolment-grace} ago (default 24 h;
 *       {@code users.mfa_enabled_notice_sent_at}). Enabling MFA needs only a
 *       password-authenticated session, so an attacker with just the password
 *       could enrol a factor of their own (found in the review of #0-83).
 *       Every enable and disable emails the account ({@code MfaService},
 *       outbox types MFA_ENABLED / MFA_DISABLED); the grace period gives the
 *       real owner that long to react, and a password reset by email within
 *       it removes the new factor ({@code MfaService#removeFactorEnrolledWithinGrace}).
 *       Counting from the notice, not from the enrolment, gives the owner the
 *       whole period also when SMTP delayed it, and an undeliverable notice
 *       keeps the factor out (all found in review). The fact is kept on the
 *       user because the outbox purges sent rows after 30 days. The usual
 *       pairing: factor-change notifications, a cool-down for sensitive
 *       operations, and recovery through a channel other than the password.</li>
 * </ol>
 *
 * <h2>Why a server-side session check, not a claim in the JWT</h2>
 * The usual way to tell a resource server how a user authenticated is an
 * {@code amr} claim (RFC 8176) in the access token. Here only auth-service
 * needs the answer, and auth-service owns the sessions: every access token
 * from a real login already names its session ({@code sessionId}, V16). So
 * the fact is stored on the session ({@code auth_tokens.mfa_verified_at},
 * V22) and looked up per request. The token format, {@code shared} and the
 * other six services stay unchanged, and the check is revocable at once
 * (logout; disabling MFA, {@link AuthTokenService#forgetMfaOfAllSessions}).
 * If another service ever needs step-up, the {@code amr} claim is the way to
 * add it.
 */
@Service
public class MfaSessionStatusService {

    /** The outcome for one session; anything but {@link #ACCEPTED} is a refusal. */
    public enum Status {
        ACCEPTED,
        /** No live session, or one that never completed MFA. */
        NO_MFA,
        /** Completed MFA longer ago than the maximum session age. */
        MFA_TOO_OLD,
        /** The email announcing the account's factor was sent less than the grace period ago. */
        MFA_ENROLLED_TOO_RECENTLY,
        /** The email announcing the account's factor has not been sent. */
        MFA_NOTICE_NOT_DELIVERED
    }

    private final AuthTokenRepository authTokenRepository;
    private final Duration maxSessionAge;
    private final Duration enrolmentGrace;

    public MfaSessionStatusService(
            AuthTokenRepository authTokenRepository,
            @Value("${platform.mfa.max-session-age:PT12H}") Duration maxSessionAge,
            @Value("${platform.mfa.enrolment-grace:PT24H}") Duration enrolmentGrace) {
        if (maxSessionAge.isNegative() || maxSessionAge.isZero() || enrolmentGrace.isNegative()) {
            throw new IllegalArgumentException("platform.mfa.max-session-age must be positive and "
                    + "platform.mfa.enrolment-grace not negative, were " + maxSessionAge + " / " + enrolmentGrace);
        }
        this.authTokenRepository = authTokenRepository;
        this.maxSessionAge = maxSessionAge;
        this.enrolmentGrace = enrolmentGrace;
    }

    /** {@link Status#NO_MFA} for a token without a session (e.g. incident-service's dev token). */
    @Transactional(readOnly = true)
    public Status check(UUID userId, String tenantId, UUID sessionId) {
        if (userId == null || tenantId == null || sessionId == null) {
            return Status.NO_MFA;
        }
        final Instant now = Instant.now();
        // Tenant-scoped like every query (CLAUDE.md), though a user id alone is unique.
        final List<MfaSessionFacts> live =
                authTokenRepository.findLiveSessionMfaFacts(userId, tenantId, sessionId, now);
        if (live.isEmpty()) {
            return Status.NO_MFA;
        }
        final MfaSessionFacts facts = live.getFirst();
        if (facts.mfaVerifiedAt() == null || !facts.mfaEnabled()) {
            return Status.NO_MFA;
        }
        if (facts.mfaVerifiedAt().isBefore(now.minus(maxSessionAge))) {
            return Status.MFA_TOO_OLD;
        }
        if (facts.mfaEnabledAt() == null || facts.mfaEnabledNoticeSentAt() == null) {
            return Status.MFA_NOTICE_NOT_DELIVERED;
        }
        if (!isEstablished(facts.mfaEnabledNoticeSentAt(), now)) {
            return Status.MFA_ENROLLED_TOO_RECENTLY;
        }
        return Status.ACCEPTED;
    }

    /**
     * Whether a factor whose notice was sent at {@code noticeSentAt} has passed
     * the grace period. A factor that has not (or whose notice was never
     * sent) is still "fresh": the platform API refuses it, and a password
     * reset removes it ({@code MfaService#removeFactorEnrolledWithinGrace}).
     */
    public boolean isEstablished(Instant noticeSentAt, Instant now) {
        return noticeSentAt != null && !noticeSentAt.isAfter(now.minus(enrolmentGrace));
    }
}
