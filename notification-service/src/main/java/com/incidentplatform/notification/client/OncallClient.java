package com.incidentplatform.notification.client;

import java.util.Optional;

public interface OncallClient {

    Optional<OncallInfo> getCurrentOncall(String tenantId, String role);

    /**
     * Looks up the on-call schedule entry for the given Slack user ID,
     * scoped to the specified tenant.
     *
     * <p>{@code tenantId} is mandatory — without it the query would search
     * all tenants, and two tenants sharing the same Slack workspace could
     * have colliding {@code slackUserId} values, returning a schedule from
     * the wrong tenant.
     *
     * <p>Returns empty if the user is not registered in the on-call schedule
     * for this tenant, or if the {@code oncall-service} endpoint is
     * unavailable (circuit breaker open).
     */
    Optional<OncallInfo> findBySlackUserId(String tenantId, String slackUserId);

    record OncallInfo(
            String userId,
            String userName,
            String email,
            String phone,
            String slackUserId,
            String role
    ) {
        public boolean hasDm()  { return slackUserId != null && !slackUserId.isBlank(); }
        public boolean hasSms() { return phone != null && !phone.isBlank(); }
    }
}