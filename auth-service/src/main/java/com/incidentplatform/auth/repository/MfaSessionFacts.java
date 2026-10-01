package com.incidentplatform.auth.repository;

import java.time.Instant;

/**
 * What the platform API's step-up check needs to know about one live login
 * session (backlog #0-83): when the session completed MFA, and whether and
 * since when the account has MFA enabled. Read in one query by
 * {@link AuthTokenRepository#findLiveSessionMfaFacts}.
 *
 * @param mfaVerifiedAt when the session completed MFA; null for a password-only login
 * @param mfaEnabled    whether the account has MFA enabled now
 * @param mfaEnabledAt  when the account's current factor was enabled; null if never
 * @param mfaEnabledNoticeSentAt when the MFA_ENABLED notice of that factor was sent; null if not (yet)
 */
public record MfaSessionFacts(Instant mfaVerifiedAt, boolean mfaEnabled, Instant mfaEnabledAt,
                              Instant mfaEnabledNoticeSentAt) {
}
