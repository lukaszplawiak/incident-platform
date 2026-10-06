package com.incidentplatform.auth.repository;

import java.time.Instant;

import com.incidentplatform.auth.domain.SuspensionMode;
import com.incidentplatform.auth.domain.TenantStatus;

/**
 * A tenant's status and suspension mode, for the per-request check (backlog
 * #0-82), and when it was suspended ({@code suspended_at}, kept across a change
 * of mode), which the other services measure a pause of background work from
 * (step 2b). {@code suspendedAt} is {@code null} for an active tenant, and on
 * the sign-in path, which does not read it.
 */
public record TenantStatusView(TenantStatus status, SuspensionMode mode, Instant suspendedAt) {
}
