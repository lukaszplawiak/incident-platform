package com.incidentplatform.shared.kafka;

import java.util.Locale;

/**
 * A consumed record whose tenant cannot be trusted (backlog #0-92): no valid
 * tenant in its payload, or a tenant header that is missing or disagrees with
 * the payload.
 * An {@link IllegalArgumentException}, so every consumer's existing poison-pill
 * handling sends the record to its dead-letter topic. The message never quotes
 * the record's values.
 */
public class TenantResolutionException extends IllegalArgumentException {

    /** Why the record's tenant was refused; also the {@code reason} tag of the refusal counter. */
    public enum Reason {
        /** The payload names no tenant. */
        MISSING,
        /** The payload's tenant, or the header, is not a valid tenant id. */
        INVALID,
        /** The header names another tenant than the payload. */
        MISMATCH,
        /** The record carries no {@code X-Tenant-Id} header (every sender writes one). */
        HEADER_MISSING;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Reason reason;

    TenantResolutionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
