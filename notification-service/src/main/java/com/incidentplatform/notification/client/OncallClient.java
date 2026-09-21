package com.incidentplatform.notification.client;

import java.util.Optional;

public interface OncallClient {

    /**
     * Looks up the current on-call person for a role, scoped to the tenant.
     *
     * <p>Returns empty only when nobody is on call (a 204). If oncall-service
     * cannot answer (unreachable, timeout, circuit open, 401/403) it throws
     * {@link OncallLookupUnavailableException} instead of returning empty
     * (backlog #0-19), so a failed lookup is never mistaken for "nobody on
     * call".
     */
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

    /**
     * Looks up the current on-call entry of one user, scoped to the specified
     * tenant. Used to notify the person an incident was escalated to
     * ({@code IncidentEscalatedEvent.escalateTo}), instead of the PRIMARY
     * on-call (backlog #0-1).
     *
     * <p>{@code tenantId} is mandatory and is matched together with
     * {@code userId} by oncall-service: {@code escalateTo} is an unverified
     * id from a Kafka payload, so it must never be resolved across tenants.
     *
     * <p>Returns empty if the user is not on call right now (a 204), or if
     * the response is for a different user than the one asked for. If
     * oncall-service cannot answer (unreachable, timeout, circuit open,
     * 401/403) it throws {@link OncallLookupUnavailableException} instead of
     * returning empty (backlog #0-19), so a failed lookup is never mistaken
     * for "not on call". The router treats "not on call" as target not found
     * and falls back to the tenant's PRIMARY on-call.
     */
    Optional<OncallInfo> findCurrentByUserId(String tenantId, String userId);

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