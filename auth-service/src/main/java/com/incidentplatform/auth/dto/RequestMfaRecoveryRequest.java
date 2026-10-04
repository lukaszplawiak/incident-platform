package com.incidentplatform.auth.dto;

import com.incidentplatform.auth.domain.MfaVerificationMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * A platform operator's request to reset the MFA of a customer tenant's only
 * admin (backlog #0-90, POST /api/v1/platform/tenants/{tenantId}/mfa-recovery).
 *
 * @param userId             the admin to recover
 * @param verificationMethod how the person was verified, outside the account's
 *                           own channels (its password and mailbox may be the
 *                           attacker's)
 * @param verificationNote   what exactly was checked (who, when, which number
 *                           or record); kept in the operator tenant's audit
 *                           trail only. One line, no control characters
 *                           (checked by the service).
 */
public record RequestMfaRecoveryRequest(

        @NotNull(message = "userId is required")
        UUID userId,

        @NotNull(message = "verificationMethod is required")
        MfaVerificationMethod verificationMethod,

        @NotBlank(message = "verificationNote is required")
        @Size(max = 500, message = "verificationNote must be at most 500 characters")
        String verificationNote

) {}
