package com.incidentplatform.auth.dto;

import java.time.Instant;

/** Response for GET /auth/mfa/backup-codes. */
public record MfaBackupCodesStatusResponse(
        long remainingCodes,
        Instant mfaEnabledAt
) {}