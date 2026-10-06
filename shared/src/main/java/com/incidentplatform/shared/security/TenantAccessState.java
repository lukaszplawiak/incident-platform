package com.incidentplatform.shared.security;

import java.time.Instant;
import java.util.Objects;

/**
 * A tenant's access as auth-service last answered it, with the time its
 * suspension began (backlog #0-82, step 2b): what
 * {@link TenantStatusProvider#confirmedStateOf} gives a caller that records
 * durable state on it.
 *
 * @param access what the tenant may do
 * @param since  when its suspension began ({@code suspended_at}); {@code null}
 *               for full access, or when the provider was not told
 */
public record TenantAccessState(TenantAccess access, Instant since) {

    public TenantAccessState {
        Objects.requireNonNull(access, "access");
    }
}
