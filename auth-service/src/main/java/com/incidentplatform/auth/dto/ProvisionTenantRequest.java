package com.incidentplatform.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A platform operator's request to create a tenant and invite its first admin
 * (backlog #0-80, POST /api/v1/platform/tenants).
 *
 * @param tenantId    a slug: lowercase letters, digits and hyphens, 3-63
 *                    characters, starting and ending with a letter or digit. It
 *                    ends up in tokens, headers, logs and every service's rows,
 *                    so new ids are kept plain. Reserved ids are refused by the
 *                    service.
 * @param displayName how the tenant is shown to operators
 * @param adminEmail  who is invited as the tenant's first admin
 */
public record ProvisionTenantRequest(

        @NotBlank(message = "tenantId is required")
        @Pattern(regexp = TenantIds.SLUG,
                message = "tenantId must be 3-63 lowercase letters, digits or hyphens, "
                        + "starting and ending with a letter or digit")
        String tenantId,

        @NotBlank(message = "displayName is required")
        @Size(max = 200, message = "displayName must be at most 200 characters")
        String displayName,

        @NotBlank(message = "adminEmail is required")
        @Email(message = "adminEmail must be a valid email address")
        @Size(max = 255, message = "adminEmail must be at most 255 characters")
        String adminEmail

) {}
