package com.incidentplatform.auth.dto;

import java.util.List;

/**
 * Response from POST /auth/mfa/enable-required.
 *
 * <p>Bundles the same one-time backup codes as {@link MfaEnableResponse}
 * with a completed {@link LoginResponse} — this endpoint both enables MFA
 * <i>and</i> finishes the login that was blocked pending setup, so the
 * client needs both pieces in a single response rather than making a
 * separate login call afterward (which would just fail again with
 * mfaRequired=true now that MFA is enabled, requiring yet another round
 * trip through /mfa/verify for no reason).
 */
public record MfaEnableWithLoginResponse(
        List<String> backupCodes,
        LoginResponse login
) {}