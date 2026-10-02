package com.incidentplatform.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(

        @NotBlank(message = "currentPassword is required")
        String currentPassword,

        @NotBlank(message = "newPassword is required")
        @Size(min = 12, message = "newPassword must be at least 12 characters")
        String newPassword,

        /**
         * Backlog #0-89: also revoke the caller's personal API keys. Optional,
         * absent = false: a routine change keeps them, a change made because
         * the password may be known to someone else ends them too (OWASP ASVS
         * 3.3.3 asks for the option). A password reset revokes them always.
         */
        Boolean revokePersonalApiKeys

) {

    /** A change that keeps the personal API keys. */
    public ChangePasswordRequest(String currentPassword, String newPassword) {
        this(currentPassword, newPassword, null);
    }

    public boolean revokesPersonalApiKeys() {
        return Boolean.TRUE.equals(revokePersonalApiKeys);
    }
}
