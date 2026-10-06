package com.incidentplatform.shared.security;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * auth-service's answer on {@code GET /api/v1/internal/tenant-status} (backlog
 * #0-82): what the calling service's tenant may do, and, for a suspended
 * tenant, since when. The reasons behind a status (mode, reason, the operator's
 * note) stay in auth-service: the other services need nothing more to enforce
 * it.
 *
 * <p>{@code since} (step 2b, found in review) is the tenant's {@code
 * suspended_at}, kept by auth-service across a change of mode. A service that
 * holds back the tenant's background work measures the pause from it, not
 * from when it noticed the suspension: the time of the event, recorded by the
 * owner of the state, not the time it was processed (a late sync no longer
 * shortens a stopped escalation timer). Left out of the JSON when absent, so a
 * FULL answer reads as before; a service reading an answer from an older
 * auth-service gets {@code null} and falls back to when it saw the suspension.
 *
 * @param access what the tenant may do now; never {@code null} in an answer
 * @param since  when the tenant was suspended; {@code null} for full access, or
 *               when auth-service did not say
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TenantStatusResponse(TenantAccess access, Instant since) {

    /** An answer without a suspension time: full access, or an older auth-service. */
    public TenantStatusResponse(TenantAccess access) {
        this(access, null);
    }
}
