package com.incidentplatform.auth.dto;

import java.time.Instant;

/**
 * Optional body of {@code POST /api/v1/users/{id}/mfa-reset}.
 *
 * @param revokeKeysCreatedSince backlog #0-89: also revoke every API key the
 *        user created at or after this time (tenant and integration keys
 *        included, which the reset alone keeps); absent = keep them
 */
public record MfaResetRequest(Instant revokeKeysCreatedSince) {
}
