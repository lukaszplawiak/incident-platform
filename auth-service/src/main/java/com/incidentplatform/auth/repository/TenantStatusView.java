package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.SuspensionMode;
import com.incidentplatform.auth.domain.TenantStatus;

/** A tenant's status and suspension mode, for the per-request check (backlog #0-82). */
public record TenantStatusView(TenantStatus status, SuspensionMode mode) {
}
