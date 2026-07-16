package com.incidentplatform.auth.dto;

/**
 * Response from POST /auth/mfa/setup.
 */
public record MfaSetupResponse(
        /** otpauth:// URL — encode into QR code for Google Authenticator. */
        String qrUrl,

        /**
         * Plain base32 secret for manual entry (when QR cannot be scanned).
         * Shown once — stored encrypted in DB as pending secret.
         */
        String secret
) {}