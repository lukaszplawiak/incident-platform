package com.incidentplatform.auth.dto;

import com.incidentplatform.auth.domain.MfaRecoveryCloseReason;
import com.incidentplatform.auth.domain.MfaRecoveryRequest;
import com.incidentplatform.auth.domain.MfaRecoveryStatus;
import com.incidentplatform.auth.domain.MfaVerificationMethod;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * An operator MFA recovery request as the platform API shows it to operators
 * (backlog #0-90).
 *
 * @param executeNotBefore when the reset may run: the notice's send time plus
 *                         the waiting period; null until the notice is sent
 */
public record MfaRecoveryRequestDto(
        UUID id,
        String tenantId,
        UUID userId,
        UUID requestedBy,
        MfaVerificationMethod verificationMethod,
        String verificationNote,
        MfaRecoveryStatus status,
        Instant createdAt,
        Instant noticeSentAt,
        Instant executeNotBefore,
        Instant closedAt,
        MfaRecoveryCloseReason closeReason,
        UUID closedBy) {

    public static MfaRecoveryRequestDto from(MfaRecoveryRequest request, Duration waitingPeriod) {
        return new MfaRecoveryRequestDto(
                request.getId(), request.getTenantId(), request.getUserId(), request.getRequestedBy(),
                request.getVerificationMethod(), request.getVerificationNote(), request.getStatus(),
                request.getCreatedAt(), request.getNoticeSentAt(),
                request.getNoticeSentAt() == null ? null : request.getNoticeSentAt().plus(waitingPeriod),
                request.getClosedAt(), request.getCloseReason(), request.getClosedBy());
    }
}
