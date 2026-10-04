package com.incidentplatform.auth.service;

import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.Objects;

/**
 * A sign-in, invite or reset could not read its tenant's status in time,
 * because a suspension or resumption of that tenant held the row (backlog
 * #0-82, {@code TenantAccessService}). Not a refusal: nothing is known about
 * the outcome yet, so 503 with Retry-After ({@code TenantStatusBusyHandler}),
 * and the client tries again once the operator's change has committed.
 */
public class TenantStatusBusyException extends BusinessException {

    private final Duration retryAfter;

    public TenantStatusBusyException(Duration retryAfter) {
        super(ErrorCodes.AUTHENTICATION_UNAVAILABLE,
                "The organisation's account is being changed right now. Retry in a few seconds.",
                HttpStatus.SERVICE_UNAVAILABLE);
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
